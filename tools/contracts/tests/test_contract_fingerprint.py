"""合约指纹算法与 catalog 失败语义测试。"""

from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from tools.contracts.contract_fingerprint import (
    ABI_ALGORITHM,
    BYTECODE_ALGORITHM,
    CATALOG_SCHEMA,
    SOURCE_ALGORITHM,
    FingerprintError,
    canonicalize_abi_document,
    fingerprint_abi,
    fingerprint_bytecode,
    load_json_strict,
    refresh_catalog,
    run_command,
    sha256_label,
    verify_catalog,
)


class ContractFingerprintTest(unittest.TestCase):
    """覆盖 canonical ABI、bytecode 和 catalog 的安全边界。"""

    def test_abi_format_order_and_internal_type_are_ignored(self) -> None:
        """格式、顶层声明顺序和 internalType 不应改变 ABI 指纹。"""
        first = [
            {
                "type": "function",
                "name": "store",
                "inputs": [{"name": "value", "type": "bytes32", "internalType": "bytes32"}],
                "outputs": [],
                "stateMutability": "nonpayable",
            },
            {"type": "event", "name": "Stored", "inputs": [], "anonymous": False},
        ]
        second = [
            {"anonymous": False, "inputs": [], "name": "Stored", "type": "event"},
            {
                "outputs": [],
                "inputs": [{"type": "bytes32", "name": "value"}],
                "stateMutability": "nonpayable",
                "name": "store",
                "type": "function",
            },
        ]

        self.assertEqual(
            canonicalize_abi_document(first),
            canonicalize_abi_document(second),
        )

    def test_parameter_order_remains_semantic(self) -> None:
        """参数数组顺序变化必须产生不同 canonical ABI。"""
        first = [{"type": "function", "name": "f", "inputs": [
            {"name": "a", "type": "uint256"},
            {"name": "b", "type": "string"},
        ]}]
        second = [{"type": "function", "name": "f", "inputs": [
            {"name": "b", "type": "string"},
            {"name": "a", "type": "uint256"},
        ]}]

        self.assertNotEqual(
            canonicalize_abi_document(first),
            canonicalize_abi_document(second),
        )

    def test_cross_language_golden_vector(self) -> None:
        """Python 实现必须与 Java 共用的 golden vector 完全一致。"""
        fixture = load_json_strict(
            Path("platform-fisco/src/test/resources/contract-registry/fingerprint-vectors.json")
        )
        for case in fixture["abiCases"]:
            with self.subTest(case=case["name"]):
                for document in case["documents"]:
                    canonical = canonicalize_abi_document(document)
                    self.assertEqual(canonical.decode("utf-8"), case["canonicalJson"])
                    self.assertEqual(sha256_label(canonical), case["abiSha256"])

    def test_duplicate_json_key_is_rejected(self) -> None:
        """重复 JSON key 不得被解析器静默覆盖。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "duplicate.json"
            path.write_text('{"contracts": [], "contracts": []}', encoding="utf-8")

            with self.assertRaisesRegex(FingerprintError, "duplicate key"):
                load_json_strict(path)

    def test_trailing_json_token_is_rejected(self) -> None:
        """合法根值之后追加第二个 JSON 值必须被严格解析器拒绝。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "trailing.json"
            path.write_text("[] []", encoding="utf-8")

            with self.assertRaisesRegex(FingerprintError, "Invalid JSON artifact"):
                load_json_strict(path)

    def test_bytecode_normalization_accepts_prefix_case_and_whitespace(self) -> None:
        """等价的 hex bytecode 文本应生成相同 bytes 指纹。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            first = root / "first.bin"
            second = root / "second.bin"
            first.write_text("0x60AA 00ff\n", encoding="ascii")
            second.write_text("60aa00FF", encoding="ascii")

            self.assertEqual(fingerprint_bytecode(first), fingerprint_bytecode(second))

    def test_invalid_bytecode_is_rejected(self) -> None:
        """空、奇数长度和非十六进制 bytecode 必须失败。"""
        for content in ("", "0x123", "0xzz"):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "invalid.bin"
                path.write_text(content, encoding="ascii")
                with self.assertRaises(FingerprintError):
                    fingerprint_bytecode(path)

    def test_compare_bytecode_uses_decoded_bytes(self) -> None:
        """部署门禁比较 bytecode 时必须忽略 0x、大小写和空白文本差异。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            expected = root / "expected.bin"
            actual = root / "actual.bin"
            expected.write_text("0x60AA00ff\n", encoding="ascii")
            actual.write_text("60aa 00FF", encoding="ascii")

            exit_code = run_command(SimpleNamespace(
                command="compare-bytecode",
                expected=expected,
                actual=actual,
            ))

            self.assertEqual(exit_code, 0)

            actual.write_text("600100ff", encoding="ascii")
            with self.assertRaisesRegex(FingerprintError, "bytecode mismatch"):
                run_command(SimpleNamespace(
                    command="compare-bytecode",
                    expected=expected,
                    actual=actual,
                ))

    def test_refresh_and_verify_catalog_detect_artifact_drift(self) -> None:
        """Catalog refresh 应稳定，任一 ABI 漂移必须使 verify 失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog_path = root / "catalog.json"
            template = self._catalog_template()
            refreshed = refresh_catalog(root, template)
            catalog_path.write_text(
                json.dumps(refreshed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )

            verified = verify_catalog(root, catalog_path)
            self.assertEqual(verified["contracts"][0]["contractName"], "Sharing")

            (root / "abi/Sharing.abi").write_text(
                '[{"type":"function","name":"changed","inputs":[]}]',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(FingerprintError, "abiSha256 drift"):
                verify_catalog(root, catalog_path)

    def test_runtime_bytecode_drift_is_independent_from_creation_bytecode(self) -> None:
        """仅 runtime 漂移时也必须失败，creation 指纹不能替代运行时锚点。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog_path = root / "catalog.json"
            refreshed = refresh_catalog(root, self._catalog_template())
            catalog_path.write_text(
                json.dumps(refreshed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (root / "bin/runtime/ecc/Sharing.bin").write_text(
                "60046004",
                encoding="ascii",
            )

            with self.assertRaisesRegex(FingerprintError, "runtime.*drift"):
                verify_catalog(root, catalog_path)

    def test_source_copy_drift_is_rejected_during_refresh(self) -> None:
        """部署源与运行时源不一致时不得生成新的 catalog。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            (root / "runtime/Sharing.sol").write_text(
                "contract Sharing { function changed() external {} }\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(FingerprintError, "source copies drift"):
                refresh_catalog(root, self._catalog_template())

    def test_duplicate_source_paths_are_rejected(self) -> None:
        """同一路径不得伪装成两份独立 Solidity 源码。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"][0]["sourcePaths"] = [
                "contract/Sharing.sol",
                "./contract/Sharing.sol",
            ]

            with self.assertRaisesRegex(FingerprintError, "distinct paths"):
                refresh_catalog(root, catalog)

    def test_required_contract_must_have_one_active_artifact(self) -> None:
        """缺少 Storage ACTIVE 条目时 CI 校验必须在启动前失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"] = catalog["contracts"][:1]

            with self.assertRaisesRegex(FingerprintError, "missing Storage"):
                refresh_catalog(root, catalog)

    def test_multiple_active_versions_are_rejected(self) -> None:
        """同一合约的多个 ACTIVE 版本不得通过 catalog 门禁。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            duplicate = copy.deepcopy(catalog["contracts"][0])
            duplicate["semanticVersion"] = "2.0.0"
            catalog["contracts"].append(duplicate)

            with self.assertRaisesRegex(FingerprintError, "Multiple ACTIVE artifacts"):
                refresh_catalog(root, catalog)

    def test_effective_at_requires_timezone(self) -> None:
        """无时区时间不得在 Python 通过后再被 Java 启动拒绝。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"][0]["effectiveAt"] = "2020-01-01T00:00:00"

            with self.assertRaisesRegex(FingerprintError, "must include a timezone"):
                refresh_catalog(root, catalog)

    def test_effective_at_rejects_non_rfc3339_separator(self) -> None:
        """Python 可解析但 Java 不接受的空格分隔时间必须提前失败。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"][0]["effectiveAt"] = "2020-01-01 00:00:00+00:00"

            with self.assertRaisesRegex(FingerprintError, "must use RFC3339 format"):
                refresh_catalog(root, catalog)

    def test_future_active_artifact_is_rejected(self) -> None:
        """未来生效的 ACTIVE artifact 不得提前通过部署门禁。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"][0]["effectiveAt"] = "2999-01-01T00:00:00Z"

            with self.assertRaisesRegex(FingerprintError, "not yet effective"):
                refresh_catalog(root, catalog)

    def test_unknown_catalog_fields_are_rejected(self) -> None:
        """版本化 schema 之外的字段不得只在 Java 反序列化阶段失败。"""
        catalog = self._catalog_template()
        catalog["unexpected"] = True

        with self.assertRaisesRegex(FingerprintError, "versioned schema"):
            refresh_catalog(Path.cwd(), catalog)

    def test_bytecode_fingerprint_maps_reject_extra_variants(self) -> None:
        """creation/runtime hash map 都必须与 Java 一样只接受 ecc 和 sm。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog_path = root / "catalog.json"
            catalog = refresh_catalog(root, self._catalog_template())
            catalog["contracts"][0]["runtimeBytecodeSha256"]["legacy"] = (
                "sha256:" + "0" * 64
            )
            catalog_path.write_text(
                json.dumps(catalog, ensure_ascii=False),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(FingerprintError, "exactly ecc and sm"):
                verify_catalog(root, catalog_path)

    def test_creation_and_runtime_must_use_distinct_artifact_paths(self) -> None:
        """同一文件不得同时冒充 creation 与 deployed runtime 制品。"""
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            self._write_artifacts(root)
            catalog = self._catalog_template()
            catalog["contracts"][0]["runtimeBytecodePaths"]["ecc"] = (
                catalog["contracts"][0]["creationBytecodePaths"]["ecc"]
            )

            with self.assertRaisesRegex(FingerprintError, "distinct artifact paths"):
                refresh_catalog(root, catalog)

    def _write_artifacts(self, root: Path) -> None:
        """创建 catalog 测试所需的最小双份源码、ABI 和 bin。"""
        for directory in (
            "contract",
            "runtime",
            "abi",
            "bin/ecc",
            "bin/sm",
            "bin/runtime/ecc",
            "bin/runtime/sm",
        ):
            (root / directory).mkdir(parents=True, exist_ok=True)
        for contract_name in ("Sharing", "Storage"):
            source = (
                f"contract {contract_name} "
                "{ function store(bytes32 value) external {} }\n"
            )
            (root / f"contract/{contract_name}.sol").write_text(source, encoding="utf-8")
            (root / f"runtime/{contract_name}.sol").write_text(source, encoding="utf-8")
            (root / f"abi/{contract_name}.abi").write_text(
                '[{"type":"function","name":"store","inputs":'
                '[{"name":"value","type":"bytes32"}],"outputs":[]}]',
                encoding="utf-8",
            )
            (root / f"bin/ecc/{contract_name}.bin").write_text(
                "60006000",
                encoding="ascii",
            )
            (root / f"bin/sm/{contract_name}.bin").write_text(
                "60016001",
                encoding="ascii",
            )
            (root / f"bin/runtime/ecc/{contract_name}.bin").write_text(
                "60026002",
                encoding="ascii",
            )
            (root / f"bin/runtime/sm/{contract_name}.bin").write_text(
                "60036003",
                encoding="ascii",
            )

    def _catalog_template(self) -> dict[str, Any]:
        """返回可由 refresh 填充指纹的最小 catalog 模板。"""
        return {
            "schemaVersion": CATALOG_SCHEMA,
            "abiFingerprintAlgorithm": ABI_ALGORITHM,
            "bytecodeFingerprintAlgorithm": BYTECODE_ALGORITHM,
            "sourceFingerprintAlgorithm": SOURCE_ALGORITHM,
            "contracts": [
                self._catalog_entry("Sharing", "1.1.0"),
                self._catalog_entry("Storage", "1.0.0"),
            ],
        }

    def _catalog_entry(self, contract_name: str, version: str) -> dict[str, Any]:
        """创建单个合约的完整 catalog 测试条目。"""
        return {
            "contractName": contract_name,
            "semanticVersion": version,
            "status": "ACTIVE",
            "effectiveAt": "2020-01-01T00:00:00Z",
            "upgradeStrategy": "REDEPLOY_ADDRESS",
            "sourcePaths": [
                f"contract/{contract_name}.sol",
                f"runtime/{contract_name}.sol",
            ],
            "sourceSha256": "sha256:" + "0" * 64,
            "abiPath": f"abi/{contract_name}.abi",
            "abiSha256": "sha256:" + "0" * 64,
            "creationBytecodePaths": {
                "ecc": f"bin/ecc/{contract_name}.bin",
                "sm": f"bin/sm/{contract_name}.bin",
            },
            "creationBytecodeSha256": {
                "ecc": "sha256:" + "0" * 64,
                "sm": "sha256:" + "0" * 64,
            },
            "runtimeBytecodePaths": {
                "ecc": f"bin/runtime/ecc/{contract_name}.bin",
                "sm": f"bin/runtime/sm/{contract_name}.bin",
            },
            "runtimeBytecodeSha256": {
                "ecc": "sha256:" + "0" * 64,
                "sm": "sha256:" + "0" * 64,
            },
        }


if __name__ == "__main__":
    unittest.main()
