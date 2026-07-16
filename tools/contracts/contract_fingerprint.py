#!/usr/bin/env python3
"""生成并校验 RecordPlatform 合约 ABI、源码和 bytecode 指纹。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

ABI_ALGORITHM = "ABI-CANONICAL-JSON-SHA256-V1"
BYTECODE_ALGORITHM = "EVM-BYTECODE-SHA256-V1"
SOURCE_ALGORITHM = "SOURCE-UTF8-LF-SHA256-V1"
CATALOG_SCHEMA = "record-platform-contract-artifacts.v2"
MAX_ARTIFACT_BYTES = 5 * 1024 * 1024
HASH_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
SEMANTIC_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
EFFECTIVE_AT_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$"
)
ALLOWED_STATUS = {"ACTIVE", "DEPRECATED", "REVOKED"}
REQUIRED_ACTIVE_CONTRACTS = {"Sharing", "Storage"}
CATALOG_FIELDS = {
    "schemaVersion",
    "abiFingerprintAlgorithm",
    "bytecodeFingerprintAlgorithm",
    "sourceFingerprintAlgorithm",
    "contracts",
}
CONTRACT_FIELDS = {
    "contractName",
    "semanticVersion",
    "status",
    "effectiveAt",
    "upgradeStrategy",
    "sourcePaths",
    "sourceSha256",
    "abiPath",
    "abiSha256",
    "creationBytecodePaths",
    "creationBytecodeSha256",
    "runtimeBytecodePaths",
    "runtimeBytecodeSha256",
}
BYTECODE_VARIANTS = ("ecc", "sm")


class FingerprintError(ValueError):
    """表示合约 artifact 或 catalog 不满足可验证合同。"""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """把 JSON object pairs 转为字典，并拒绝会产生歧义的重复 key。"""
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise FingerprintError(f"JSON object contains duplicate key: {key}")
        result[key] = value
    return result


def _reject_non_finite_number(value: str) -> None:
    """拒绝 JSON 标准之外的 NaN 和 Infinity。"""
    raise FingerprintError(f"JSON contains non-finite number: {value}")


def _read_bounded_bytes(path: Path) -> bytes:
    """读取大小受限的 artifact，避免意外扫描巨型文件。"""
    try:
        size = path.stat().st_size
    except OSError as exc:
        raise FingerprintError(f"Cannot stat artifact: {path}: {exc}") from exc
    if size <= 0:
        raise FingerprintError(f"Artifact is empty: {path}")
    if size > MAX_ARTIFACT_BYTES:
        raise FingerprintError(
            f"Artifact exceeds {MAX_ARTIFACT_BYTES} bytes: {path}"
        )
    try:
        return path.read_bytes()
    except OSError as exc:
        raise FingerprintError(f"Cannot read artifact: {path}: {exc}") from exc


def load_json_strict(path: Path) -> Any:
    """以严格 JSON 规则读取大小受限的 UTF-8 文件。"""
    raw = _read_bounded_bytes(path)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise FingerprintError(f"JSON artifact is not UTF-8: {path}") from exc
    try:
        return json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_non_finite_number,
        )
    except json.JSONDecodeError as exc:
        raise FingerprintError(f"Invalid JSON artifact {path}: {exc}") from exc


def _normalize_abi_value(value: Any) -> Any:
    """递归规范 ABI JSON，同时移除不影响 ABI 编码的 internalType。"""
    if isinstance(value, dict):
        return {
            key: _normalize_abi_value(value[key])
            for key in sorted(value)
            if key != "internalType"
        }
    if isinstance(value, list):
        return [_normalize_abi_value(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise FingerprintError(f"Unsupported ABI JSON value type: {type(value).__name__}")


def _compact_json(value: Any) -> str:
    """输出 UTF-8 语义一致的确定性 compact JSON。"""
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    except (TypeError, ValueError) as exc:
        raise FingerprintError(f"Cannot serialize canonical JSON: {exc}") from exc


def canonicalize_abi_document(document: Any) -> bytes:
    """把 ABI array 转为忽略声明顺序和格式差异的 canonical bytes。"""
    if not isinstance(document, list):
        raise FingerprintError("ABI root must be a JSON array")
    normalized_entries = [_normalize_abi_value(entry) for entry in document]
    for entry in normalized_entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("type"), str):
            raise FingerprintError("Every ABI entry must be an object with a string type")
    normalized_entries.sort(key=_compact_json)
    return _compact_json(normalized_entries).encode("utf-8")


def sha256_label(data: bytes) -> str:
    """返回带算法前缀的 lowercase SHA-256。"""
    return f"sha256:{hashlib.sha256(data).hexdigest()}"


def fingerprint_abi(path: Path) -> str:
    """计算 ABI-CANONICAL-JSON-SHA256-V1 指纹。"""
    return sha256_label(canonicalize_abi_document(load_json_strict(path)))


def normalize_bytecode(raw: bytes, path: Path | None = None) -> bytes:
    """把可选 0x 前缀和空白的 EVM hex bytecode 转为原始 bytes。"""
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as exc:
        raise FingerprintError(f"Bytecode is not ASCII hex: {path or '<memory>'}") from exc
    compact = "".join(text.split())
    if compact.lower().startswith("0x"):
        compact = compact[2:]
    if not compact:
        raise FingerprintError(f"Bytecode is empty: {path or '<memory>'}")
    if len(compact) % 2 != 0:
        raise FingerprintError(f"Bytecode has odd hex length: {path or '<memory>'}")
    if re.fullmatch(r"[0-9a-fA-F]+", compact) is None:
        raise FingerprintError(f"Bytecode contains non-hex characters: {path or '<memory>'}")
    return bytes.fromhex(compact)


def fingerprint_bytecode(path: Path) -> str:
    """计算 EVM-BYTECODE-SHA256-V1 指纹。"""
    return sha256_label(normalize_bytecode(_read_bounded_bytes(path), path))


def fingerprint_bytecode_stream(stream: Any) -> str:
    """从大小受限的二进制流计算 EVM bytecode 指纹。"""
    raw = stream.read(MAX_ARTIFACT_BYTES + 1)
    if not raw:
        raise FingerprintError("Bytecode is empty: <stdin>")
    if len(raw) > MAX_ARTIFACT_BYTES:
        raise FingerprintError(
            f"Artifact exceeds {MAX_ARTIFACT_BYTES} bytes: <stdin>"
        )
    return sha256_label(normalize_bytecode(raw, Path("<stdin>")))


def fingerprint_source(path: Path) -> str:
    """以 UTF-8 和 LF 换行规范计算 Solidity 源码指纹。"""
    raw = _read_bounded_bytes(path)
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise FingerprintError(f"Source is not UTF-8: {path}") from exc
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return sha256_label(normalized.encode("utf-8"))


def _resolve_catalog_path(project_root: Path, relative_path: Any) -> Path:
    """解析 catalog 相对路径并阻止目录穿越到项目根之外。"""
    if not isinstance(relative_path, str) or not relative_path.strip():
        raise FingerprintError("Catalog artifact path must be a non-empty string")
    root = project_root.resolve()
    candidate = (root / relative_path).resolve()
    try:
        candidate.relative_to(root)
    except ValueError as exc:
        raise FingerprintError(f"Catalog path escapes project root: {relative_path}") from exc
    return candidate


def _validate_hash(value: Any, field_name: str) -> str:
    """校验 catalog 中统一的 SHA-256 文本格式。"""
    if not isinstance(value, str) or HASH_PATTERN.fullmatch(value) is None:
        raise FingerprintError(f"Invalid {field_name}: expected sha256:<64 lowercase hex>")
    return value


def _validate_catalog_header(catalog: Any) -> dict[str, Any]:
    """校验 catalog schema 与版本化算法声明。"""
    if not isinstance(catalog, dict):
        raise FingerprintError("Artifact catalog root must be an object")
    if set(catalog) != CATALOG_FIELDS:
        raise FingerprintError("Artifact catalog fields do not match the versioned schema")
    expected = {
        "schemaVersion": CATALOG_SCHEMA,
        "abiFingerprintAlgorithm": ABI_ALGORITHM,
        "bytecodeFingerprintAlgorithm": BYTECODE_ALGORITHM,
        "sourceFingerprintAlgorithm": SOURCE_ALGORITHM,
    }
    for field, value in expected.items():
        if catalog.get(field) != value:
            raise FingerprintError(f"Catalog {field} must equal {value}")
    contracts = catalog.get("contracts")
    if not isinstance(contracts, list) or not contracts:
        raise FingerprintError("Catalog contracts must be a non-empty array")
    return catalog


def _validate_contract_metadata(contract: dict[str, Any]) -> tuple[str, str]:
    """校验一条 catalog entry 的稳定身份与生命周期字段。"""
    if set(contract) != CONTRACT_FIELDS:
        raise FingerprintError("Contract artifact fields do not match the versioned schema")
    name = contract.get("contractName")
    version = contract.get("semanticVersion")
    if not isinstance(name, str) or re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name) is None:
        raise FingerprintError("contractName must be an identifier")
    if not isinstance(version, str) or SEMANTIC_VERSION_PATTERN.fullmatch(version) is None:
        raise FingerprintError(f"Invalid semanticVersion for {name}")
    if contract.get("status") not in ALLOWED_STATUS:
        raise FingerprintError(f"Invalid status for {name}@{version}")
    if contract.get("upgradeStrategy") != "REDEPLOY_ADDRESS":
        raise FingerprintError(f"Unsupported upgradeStrategy for {name}@{version}")
    effective_at = contract.get("effectiveAt")
    if not isinstance(effective_at, str):
        raise FingerprintError(f"effectiveAt is required for {name}@{version}")
    try:
        parsed_effective_at = datetime.fromisoformat(effective_at.replace("Z", "+00:00"))
    except ValueError as exc:
        raise FingerprintError(f"Invalid effectiveAt for {name}@{version}") from exc
    if parsed_effective_at.tzinfo is None or parsed_effective_at.utcoffset() is None:
        raise FingerprintError(f"effectiveAt must include a timezone for {name}@{version}")
    if EFFECTIVE_AT_PATTERN.fullmatch(effective_at) is None:
        raise FingerprintError(f"effectiveAt must use RFC3339 format for {name}@{version}")
    if (
        contract.get("status") == "ACTIVE"
        and parsed_effective_at.astimezone(timezone.utc) > datetime.now(timezone.utc)
    ):
        raise FingerprintError(f"ACTIVE artifact is not yet effective: {name}@{version}")
    return name, version


def _validate_catalog_lifecycle(contracts: list[dict[str, Any]]) -> None:
    """确保每个生产合约恰好存在一个 ACTIVE artifact。"""
    active_contracts: set[str] = set()
    for contract in contracts:
        if contract["status"] != "ACTIVE":
            continue
        name = contract["contractName"]
        if name in active_contracts:
            raise FingerprintError(f"Multiple ACTIVE artifacts for {name}")
        active_contracts.add(name)
    missing = sorted(REQUIRED_ACTIVE_CONTRACTS - active_contracts)
    if missing:
        raise FingerprintError(
            "Sharing and Storage must each have one ACTIVE artifact; missing "
            + ", ".join(missing)
        )


def _resolve_bytecode_paths(
    project_root: Path,
    contract: dict[str, Any],
    identity: tuple[str, str],
    field_name: str,
) -> dict[str, Path]:
    """解析且严格校验一组 ECC/SM creation 或 runtime artifact 路径。"""
    paths = contract.get(field_name)
    if not isinstance(paths, dict) or set(paths) != set(BYTECODE_VARIANTS):
        raise FingerprintError(
            f"{identity[0]} {field_name} must contain exactly ecc and sm"
        )
    return {
        variant: _resolve_catalog_path(project_root, paths[variant])
        for variant in BYTECODE_VARIANTS
    }


def refresh_catalog(project_root: Path, catalog: dict[str, Any]) -> dict[str, Any]:
    """重算 catalog 中所有 artifact 指纹并返回不修改输入的副本。"""
    refreshed = copy.deepcopy(_validate_catalog_header(catalog))
    seen: set[tuple[str, str]] = set()
    validated_contracts: list[tuple[dict[str, Any], tuple[str, str]]] = []
    for contract in refreshed["contracts"]:
        if not isinstance(contract, dict):
            raise FingerprintError("Every catalog contract entry must be an object")
        identity = _validate_contract_metadata(contract)
        if identity in seen:
            raise FingerprintError(f"Duplicate catalog contract entry: {identity[0]}@{identity[1]}")
        seen.add(identity)
        validated_contracts.append((contract, identity))

    _validate_catalog_lifecycle([contract for contract, _ in validated_contracts])

    for contract, identity in validated_contracts:
        source_paths = contract.get("sourcePaths")
        if not isinstance(source_paths, list) or len(source_paths) < 2:
            raise FingerprintError(f"{identity[0]} must declare both Solidity source copies")
        resolved_source_paths = [
            _resolve_catalog_path(project_root, source_path)
            for source_path in source_paths
        ]
        if len(set(resolved_source_paths)) != len(resolved_source_paths):
            raise FingerprintError(
                f"{identity[0]} Solidity source copies must use distinct paths"
            )
        source_hashes = {
            fingerprint_source(source_path)
            for source_path in resolved_source_paths
        }
        if len(source_hashes) != 1:
            raise FingerprintError(f"Solidity source copies drift for {identity[0]}")
        contract["sourceSha256"] = source_hashes.pop()

        abi_path = _resolve_catalog_path(project_root, contract.get("abiPath"))
        contract["abiSha256"] = fingerprint_abi(abi_path)

        creation_paths = _resolve_bytecode_paths(
            project_root,
            contract,
            identity,
            "creationBytecodePaths",
        )
        runtime_paths = _resolve_bytecode_paths(
            project_root,
            contract,
            identity,
            "runtimeBytecodePaths",
        )
        if len(set(creation_paths.values()) | set(runtime_paths.values())) != 4:
            raise FingerprintError(
                f"{identity[0]} creation/runtime variants must use distinct artifact paths"
            )
        contract["creationBytecodeSha256"] = {
            variant: fingerprint_bytecode(creation_paths[variant])
            for variant in BYTECODE_VARIANTS
        }
        contract["runtimeBytecodeSha256"] = {
            variant: fingerprint_bytecode(runtime_paths[variant])
            for variant in BYTECODE_VARIANTS
        }
    return refreshed


def verify_catalog(project_root: Path, catalog_path: Path) -> dict[str, Any]:
    """重算并逐字段比较 catalog，发现任何 artifact 漂移时失败。"""
    catalog = _validate_catalog_header(load_json_strict(catalog_path))
    refreshed = refresh_catalog(project_root, catalog)
    for expected, actual in zip(catalog["contracts"], refreshed["contracts"]):
        name = expected.get("contractName", "<unknown>")
        for field in ("sourceSha256", "abiSha256"):
            _validate_hash(expected.get(field), f"{name}.{field}")
            if expected[field] != actual[field]:
                raise FingerprintError(
                    f"{name} {field} drift: expected {expected[field]}, actual {actual[field]}"
                )
        for artifact_kind in ("creation", "runtime"):
            field = f"{artifact_kind}BytecodeSha256"
            expected_bytecode = expected.get(field)
            if (
                not isinstance(expected_bytecode, dict)
                or set(expected_bytecode) != set(BYTECODE_VARIANTS)
            ):
                raise FingerprintError(
                    f"{name}.{field} must contain exactly ecc and sm"
                )
            for variant in BYTECODE_VARIANTS:
                _validate_hash(
                    expected_bytecode.get(variant),
                    f"{name}.{field}.{variant}",
                )
                if expected_bytecode[variant] != actual[field][variant]:
                    raise FingerprintError(
                        f"{name} {variant} {artifact_kind} bytecode drift: expected "
                        f"{expected_bytecode[variant]}, actual "
                        f"{actual[field][variant]}"
                    )
    return catalog


def write_catalog(path: Path, catalog: dict[str, Any]) -> None:
    """以确定性格式写出 catalog，并确保父目录存在。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2, sort_keys=False) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    """创建指纹工具命令行解析器。"""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    verify = subparsers.add_parser("verify", help="验证 catalog 与签入 artifacts")
    verify.add_argument("--project-root", type=Path, default=Path.cwd())
    verify.add_argument("--catalog", type=Path, required=True)

    refresh = subparsers.add_parser("refresh", help="重算 catalog 并写到显式输出文件")
    refresh.add_argument("--project-root", type=Path, default=Path.cwd())
    refresh.add_argument("--catalog", type=Path, required=True)
    refresh.add_argument("--output", type=Path, required=True)

    compare = subparsers.add_parser("compare-abi", help="比较两份 ABI canonical 指纹")
    compare.add_argument("--expected", type=Path, required=True)
    compare.add_argument("--actual", type=Path, required=True)

    compare_bytecode = subparsers.add_parser(
        "compare-bytecode",
        help="比较两份 EVM bytecode 指纹",
    )
    compare_bytecode.add_argument("--expected", type=Path, required=True)
    actual_bytecode = compare_bytecode.add_mutually_exclusive_group(required=True)
    actual_bytecode.add_argument("--actual", type=Path)
    actual_bytecode.add_argument(
        "--actual-stdin",
        action="store_true",
        help="从 stdin 读取实际 bytecode，避免把链上 code 写入临时文件",
    )

    fingerprint = subparsers.add_parser("fingerprint", help="输出单个 ABI 或 bytecode 指纹")
    group = fingerprint.add_mutually_exclusive_group(required=True)
    group.add_argument("--abi", type=Path)
    group.add_argument("--bytecode", type=Path)

    return parser


def run_command(args: argparse.Namespace) -> int:
    """执行已解析的子命令并返回进程退出码。"""
    if args.command == "verify":
        catalog = verify_catalog(args.project_root, args.catalog)
        print(f"Verified {len(catalog['contracts'])} contract artifact entries")
        return 0
    if args.command == "refresh":
        catalog = _validate_catalog_header(load_json_strict(args.catalog))
        write_catalog(args.output, refresh_catalog(args.project_root, catalog))
        print(f"Wrote refreshed catalog: {args.output}")
        return 0
    if args.command == "compare-abi":
        expected = fingerprint_abi(args.expected)
        actual = fingerprint_abi(args.actual)
        if expected != actual:
            raise FingerprintError(
                f"Canonical ABI mismatch: expected {expected}, actual {actual}"
            )
        print(expected)
        return 0
    if args.command == "compare-bytecode":
        expected = fingerprint_bytecode(args.expected)
        actual = (
            fingerprint_bytecode_stream(sys.stdin.buffer)
            if getattr(args, "actual_stdin", False)
            else fingerprint_bytecode(args.actual)
        )
        if expected != actual:
            raise FingerprintError(
                f"EVM bytecode mismatch: expected {expected}, actual {actual}"
            )
        print(expected)
        return 0
    if args.command == "fingerprint":
        print(
            fingerprint_abi(args.abi)
            if args.abi is not None
            else fingerprint_bytecode(args.bytecode)
        )
        return 0
    raise FingerprintError(f"Unsupported command: {args.command}")


def main(argv: Sequence[str] | None = None) -> int:
    """运行 CLI，并把可预期的验证失败转换为简洁非零退出。"""
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return run_command(args)
    except FingerprintError as exc:
        print(f"contract fingerprint error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
