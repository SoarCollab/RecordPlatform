#!/usr/bin/env python3
"""Run the project-packaged SDK helper with bounded, secret-safe output handling."""

from __future__ import annotations

import hashlib
import json
import os
import re
import selectors
import stat
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import BinaryIO

MAX_OUTPUT = 4096
MAX_ARTIFACT = 5 * 1024 * 1024
SCHEMA = "record-platform-verified-submission.v1"


def _strict_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """Reject duplicate fields even when they repeat an identical value."""
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def parse_result(raw: bytes, name: str, variant: str) -> dict[str, str]:
    """Accept one complete bounded success record and no auxiliary output."""
    if not raw or len(raw) > MAX_OUTPUT:
        raise ValueError("output bounds")
    result = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_pairs)
    fields = {
        "schemaVersion",
        "contractName",
        "variant",
        "contractAddress",
        "transactionHash",
        "signerAddress",
    }
    if not isinstance(result, dict) or set(result) != fields:
        raise ValueError("result schema")
    if (
        result["schemaVersion"] != SCHEMA
        or result["contractName"] != name
        or result["variant"] != variant
    ):
        raise ValueError("result identity")
    for field, size in (
        ("contractAddress", 40),
        ("transactionHash", 64),
        ("signerAddress", 40),
    ):
        value = result[field]
        if (
            not isinstance(value, str)
            or not re.fullmatch(f"0x[0-9a-f]{{{size}}}", value)
            or int(value[2:], 16) == 0
        ):
            raise ValueError("result field")
    return result


def read_safe(path: Path) -> bytes:
    """Capture a regular file using no-follow ancestor/file handles and a nonblocking open."""
    path = path.absolute()
    if ".." in path.parts:
        raise ValueError("non-normal path")
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory = os.open(path.anchor, flags)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, flags, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(
            path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory
        )
        try:
            metadata = os.fstat(descriptor)
            if (
                not stat.S_ISREG(metadata.st_mode)
                or not 0 < metadata.st_size <= MAX_ARTIFACT
            ):
                raise ValueError("artifact bounds or type")
            captured = bytearray()
            while len(captured) <= MAX_ARTIFACT:
                chunk = os.read(
                    descriptor, min(65536, MAX_ARTIFACT + 1 - len(captured))
                )
                if not chunk:
                    break
                captured.extend(chunk)
            if not 0 < len(captured) <= MAX_ARTIFACT:
                raise ValueError("capture bounds")
            return bytes(captured)
        finally:
            os.close(descriptor)
    finally:
        os.close(directory)


def capture_input(
    root: Path, catalog: Path, name: str, variant: str, catalog_hash: str
) -> tuple[bytes, str]:
    """Capture artifact bytes once and derive the raw ABI digest from that same safe capture."""
    if name not in {"Storage", "Sharing"} or variant not in {"ecc", "sm"}:
        raise ValueError("unsupported artifact")
    catalog_bytes = read_safe(catalog)
    if "sha256:" + hashlib.sha256(catalog_bytes).hexdigest() != catalog_hash:
        raise ValueError("catalog changed before capture")
    document = json.loads(
        catalog_bytes.decode("utf-8"), object_pairs_hook=_strict_pairs
    )
    matches = [
        entry
        for entry in document["contracts"]
        if entry["contractName"] == name and entry["status"] == "ACTIVE"
    ]
    if len(matches) != 1:
        raise ValueError("ambiguous artifact")
    artifact = matches[0]
    captures = [catalog_bytes]
    for relative in (artifact["abiPath"], artifact["creationBytecodePaths"][variant]):
        path = Path(relative)
        if path.is_absolute() or ".." in path.parts or path.as_posix() != relative:
            raise ValueError("unsafe artifact path")
        captures.append(read_safe(root / path))
    framed = b"".join(struct.pack(">I", len(value)) + value for value in captures)
    raw_abi_hash = "sha256:" + hashlib.sha256(captures[1]).hexdigest()
    return framed, raw_abi_hash


def run_helper(
    command: list[str], timeout: float = 120, captured: bytes = b""
) -> bytes:
    """Bound stdout while discarding all SDK diagnostics; never retry uncertain writes."""
    environment = os.environ.copy()
    for key in (
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH",
        "LOADER_PATH",
        "LOADER_MAIN",
    ):
        environment.pop(key, None)
    # A private handle avoids stdin/stdout pipe deadlocks and is never reopened by pathname.
    with tempfile.TemporaryFile() as snapshot:
        snapshot.write(captured)
        snapshot.seek(0)
        return _run_captured(command, environment, snapshot, timeout)


def _run_captured(
    command: list[str], environment: dict[str, str], snapshot: BinaryIO, timeout: float
) -> bytes:
    """Launch once with a private immutable input handle and enforce the overall deadline."""
    with subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        stdin=snapshot,
        env=environment,
    ) as process:
        assert process.stdout is not None
        output = bytearray()
        deadline = time.monotonic() + timeout
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                while selector.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError("submission uncertain")
                    for key, _ in selector.select(min(remaining, 0.5)):
                        chunk = os.read(key.fd, MAX_OUTPUT + 1)
                        if not chunk:
                            selector.unregister(key.fileobj)
                        output.extend(chunk)
                        if len(output) > MAX_OUTPUT:
                            raise ValueError("oversized helper output")
            status = process.wait(timeout=max(0.001, deadline - time.monotonic()))
            if status != 0:
                raise ValueError("helper failed or uncertain")
            return bytes(output)
        except (ValueError, TimeoutError, subprocess.TimeoutExpired):
            process.kill()
            process.wait()
            raise


def main() -> int:
    """Print only validated address/transaction/signer metadata or a constant error."""
    try:
        if len(sys.argv) != 7:
            raise ValueError("arguments")
        jar, root, catalog, catalog_hash, name, variant = sys.argv[1:]
        path = Path(jar)
        if path.is_symlink() or not path.is_file():
            raise ValueError("build the project helper first")
        captured, abi_hash = capture_input(
            Path(root), Path(catalog), name, variant, catalog_hash
        )
        raw = run_helper(
            ["java", "-jar", jar, catalog_hash, name, variant, abi_hash],
            captured=captured,
        )
        result = parse_result(raw, name, variant)
        print(
            "\t".join(
                result[field]
                for field in ("contractAddress", "transactionHash", "signerAddress")
            )
        )
        return 0
    except (
        OSError,
        ValueError,
        KeyError,
        TypeError,
        RecursionError,
        TimeoutError,
        subprocess.TimeoutExpired,
    ):
        print(
            "Verified submission failed or uncertain; do not retry automatically.",
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
