#!/usr/bin/env python3
"""Provision and verify the exact FISCO Solidity compiler pair."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Any


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_MANIFEST = SCRIPT_DIR / "fisco-solc-toolchains.json"
METADATA_NAME = "provenance.json"


class ProvisionError(RuntimeError):
    """Raised when compiler provenance or cache integrity is invalid."""


def sha256_file(path: Path) -> str:
    """Return the lowercase SHA-256 digest of a regular non-symlink file."""
    metadata = path.lstat()
    if path.is_symlink() or not stat.S_ISREG(metadata.st_mode):
        raise ProvisionError(f"expected a regular non-symlink file: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest(path: Path) -> tuple[dict[str, Any], str]:
    """Load the reviewed manifest and return it with its exact digest."""
    if path.is_symlink() or not path.is_file():
        raise ProvisionError(f"manifest must be a regular non-symlink file: {path}")
    raw = path.read_bytes()
    manifest = json.loads(raw)
    if manifest.get("schemaVersion") != "record-platform-fisco-solc-toolchains.v1":
        raise ProvisionError("unsupported FISCO solc toolchain manifest schema")
    if set(manifest.get("variants", {})) != {"ecc", "sm"}:
        raise ProvisionError("manifest must define exactly ecc and sm variants")
    return manifest, hashlib.sha256(raw).hexdigest()


def normalized_platform() -> tuple[str, str]:
    """Return normalized system and machine names for manifest matching."""
    system = platform.system().lower()
    machine = platform.machine().lower()
    if machine in {"amd64", "x64"}:
        machine = "x86_64"
    return system, machine


def validate_platform(manifest: dict[str, Any]) -> dict[str, Any]:
    """Require the current host to match one reviewed platform contract."""
    system, machine = normalized_platform()
    for candidate in manifest["supportedPlatforms"]:
        if candidate["system"] == system and candidate["machine"] == machine:
            return candidate
    raise ProvisionError(f"unsupported build platform: {system}/{machine}")


def release_dir(cache_dir: Path, manifest: dict[str, Any]) -> Path:
    """Resolve the immutable cache release path for the pinned source commit."""
    return cache_dir / manifest["sourceCommit"]


def run(command: list[str], *, cwd: Path | None = None) -> str:
    """Run one bounded build/provenance command and return standard output."""
    result = subprocess.run(
        command,
        cwd=cwd,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if result.returncode != 0:
        tail = "\n".join(result.stdout.splitlines()[-30:])
        raise ProvisionError(f"command failed ({' '.join(command)}):\n{tail}")
    return result.stdout


def verify_version(compiler: Path, expected_line: str) -> str:
    """Require one compiler to report the exact reviewed release identity."""
    output = run([str(compiler), "--version"])
    if expected_line not in output.splitlines():
        raise ProvisionError(
            f"compiler {compiler} does not report exact version line: {expected_line}"
        )
    return output


def verify_release(
    cache_dir: Path,
    manifest_path: Path,
    requested_variant: str | None = None,
    requested_compiler: Path | None = None,
) -> dict[str, Path]:
    """Verify cache metadata, paths, executable hashes and version identities."""
    manifest, manifest_sha = load_manifest(manifest_path)
    validate_platform(manifest)
    target = release_dir(cache_dir, manifest)
    if target.is_symlink() or not target.is_dir():
        raise ProvisionError(f"verified compiler cache is absent: {target}")
    metadata_path = target / METADATA_NAME
    if metadata_path.is_symlink() or not metadata_path.is_file():
        raise ProvisionError(f"compiler provenance metadata is absent: {metadata_path}")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    required_metadata = {
        "schemaVersion": "record-platform-fisco-solc-provenance.v1",
        "manifestSha256": manifest_sha,
        "sourceCommit": manifest["sourceCommit"],
        "releasePrereleaseMarker": "empty",
    }
    for key, expected in required_metadata.items():
        if metadata.get(key) != expected:
            raise ProvisionError(f"compiler provenance mismatch for {key}")

    variants = [requested_variant] if requested_variant else ["ecc", "sm"]
    resolved: dict[str, Path] = {}
    for variant in variants:
        if variant not in manifest["variants"]:
            raise ProvisionError(f"unsupported compiler variant: {variant}")
        compiler = target / variant / "solc"
        if requested_compiler is not None:
            compiler = requested_compiler.absolute()
            if compiler != (target / variant / "solc").absolute():
                raise ProvisionError("explicit compiler path is outside the verified cache release")
        actual_sha = sha256_file(compiler)
        expected_sha = manifest["variants"][variant]["validatedExecutableSha256"]
        if actual_sha != expected_sha:
            raise ProvisionError(f"{variant} compiler SHA-256 mismatch")
        variant_metadata = metadata.get("variants", {}).get(variant, {})
        if variant_metadata.get("sha256") != actual_sha:
            raise ProvisionError(f"{variant} provenance executable SHA-256 mismatch")
        for key in ("buildGm", "versionLine"):
            if variant_metadata.get(key) != manifest["variants"][variant][key]:
                raise ProvisionError(f"{variant} provenance mismatch for {key}")
        verify_version(compiler, manifest["variants"][variant]["versionLine"])
        resolved[variant] = compiler
    return resolved


def checkout_source(source_dir: Path, manifest: dict[str, Any], work_dir: Path) -> Path:
    """Copy an exact Git commit into an isolated release-mode source tree."""
    if source_dir.is_symlink() or not source_dir.is_dir():
        raise ProvisionError(f"source directory must be a non-symlink directory: {source_dir}")
    actual_commit = run(["git", "-C", str(source_dir), "rev-parse", "HEAD"]).strip()
    if actual_commit != manifest["sourceCommit"]:
        raise ProvisionError(
            f"source commit mismatch: expected {manifest['sourceCommit']}, got {actual_commit}"
        )
    archive = work_dir / "source.tar"
    with archive.open("wb") as output:
        result = subprocess.run(
            ["git", "-C", str(source_dir), "archive", "--format=tar", "HEAD"],
            check=False,
            stdout=output,
        )
    if result.returncode != 0:
        raise ProvisionError("could not export pinned source commit")
    extracted = work_dir / "source"
    extracted.mkdir()
    run(["tar", "-xf", str(archive), "-C", str(extracted)])
    (extracted / "prerelease.txt").write_bytes(b"")
    (extracted / "commit_hash.txt").write_text(
        manifest["sourceCommit"] + "\n", encoding="ascii"
    )
    return extracted


def download_source_dependencies(source: Path, manifest: dict[str, Any]) -> None:
    """Download and verify every reviewed upstream build dependency archive."""
    for dependency in manifest.get("sourceDependencies", []):
        relative_path = Path(dependency["path"])
        if relative_path.is_absolute() or ".." in relative_path.parts:
            raise ProvisionError(
                f"unsafe source dependency path for {dependency.get('name', 'unknown')}"
            )
        destination = source / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
        if temporary.exists() or temporary.is_symlink():
            raise ProvisionError(f"source dependency staging path exists: {temporary}")
        try:
            request = urllib.request.Request(
                dependency["url"], headers={"User-Agent": "RecordPlatform-toolchain/1"}
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                with temporary.open("xb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
            actual_sha = sha256_file(temporary)
            if actual_sha != dependency["sha256"]:
                raise ProvisionError(
                    f"source dependency SHA-256 mismatch for {dependency['name']}"
                )
            os.replace(temporary, destination)
        finally:
            if temporary.exists():
                temporary.unlink()


def build_variant(source: Path, work_dir: Path, variant: str, manifest: dict[str, Any]) -> Path:
    """Build one compiler variant serially from the isolated release source."""
    build_dir = work_dir / f"build-{variant}"
    build_gm = "ON" if manifest["variants"][variant]["buildGm"] else "OFF"
    arguments = [
        "cmake",
        "-S",
        str(source),
        "-B",
        str(build_dir),
        *manifest["releaseBuild"]["cmakeArguments"],
        f"-DBUILD_GM={build_gm}",
    ]
    run(arguments)
    run(["cmake", "--build", str(build_dir), "--target", "boost", "--parallel", "2"])
    run(["cmake", "--build", str(build_dir), "--target", "solc", "--parallel", "2"])
    compiler = build_dir / "solc" / "solc"
    verify_version(compiler, manifest["variants"][variant]["versionLine"])
    actual_sha = sha256_file(compiler)
    expected_sha = manifest["variants"][variant]["validatedExecutableSha256"]
    if actual_sha != expected_sha:
        raise ProvisionError(
            f"{variant} compiler differs from reviewed Linux x86_64 executable: {actual_sha}"
        )
    return compiler


def provision(
    cache_dir: Path, manifest_path: Path, source_dir: Path | None
) -> dict[str, Path]:
    """Build both exact compilers and atomically publish a complete cache release."""
    manifest, manifest_sha = load_manifest(manifest_path)
    platform_contract = validate_platform(manifest)
    cache_dir = cache_dir.absolute()
    if cache_dir.exists() and (cache_dir.is_symlink() or not cache_dir.is_dir()):
        raise ProvisionError(f"cache root must be a non-symlink directory: {cache_dir}")
    cache_dir.mkdir(parents=True, exist_ok=True)
    target = release_dir(cache_dir, manifest)
    if target.exists() or target.is_symlink():
        return verify_release(cache_dir, manifest_path)

    with tempfile.TemporaryDirectory(prefix="record-platform-fisco-solc-") as temporary:
        work_dir = Path(temporary)
        if source_dir is None:
            source_dir = work_dir / "checkout"
            run([
                "git",
                "clone",
                "--filter=blob:none",
                "--no-checkout",
                manifest["sourceRepository"],
                str(source_dir),
            ])
            run([
                "git",
                "-C",
                str(source_dir),
                "checkout",
                "--detach",
                manifest["sourceCommit"],
            ])
        source = checkout_source(source_dir, manifest, work_dir)
        download_source_dependencies(source, manifest)
        built: dict[str, Path] = {}
        for variant in manifest["releaseBuild"]["buildOrder"]:
            built[variant] = build_variant(source, work_dir, variant, manifest)

        staging = cache_dir / f".{manifest['sourceCommit']}.{os.getpid()}.tmp"
        if staging.exists() or staging.is_symlink():
            raise ProvisionError(f"staging path already exists: {staging}")
        staging.mkdir(mode=0o700)
        try:
            variant_metadata: dict[str, Any] = {}
            for variant in ("ecc", "sm"):
                destination_dir = staging / variant
                destination_dir.mkdir(mode=0o700)
                destination = destination_dir / "solc"
                shutil.copyfile(built[variant], destination, follow_symlinks=False)
                destination.chmod(0o700)
                variant_metadata[variant] = {
                    "buildGm": manifest["variants"][variant]["buildGm"],
                    "sha256": sha256_file(destination),
                    "versionLine": manifest["variants"][variant]["versionLine"],
                }
            metadata = {
                "schemaVersion": "record-platform-fisco-solc-provenance.v1",
                "manifestSha256": manifest_sha,
                "sourceRepository": manifest["sourceRepository"],
                "sourceCommit": manifest["sourceCommit"],
                "releasePrereleaseMarker": "empty",
                "platform": platform_contract,
                "variants": variant_metadata,
            }
            (staging / METADATA_NAME).write_text(
                json.dumps(metadata, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            os.replace(staging, target)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    return verify_release(cache_dir, manifest_path)


def parse_args() -> argparse.Namespace:
    """Parse the explicit provision/verify/resolve command interface."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--cache-dir", type=Path, required=True)
    subparsers = parser.add_subparsers(dest="command", required=True)
    provision_parser = subparsers.add_parser("provision")
    provision_parser.add_argument(
        "--source-dir",
        type=Path,
        help="existing exact checkout; omit to clone the reviewed repository explicitly",
    )
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("--variant", choices=("ecc", "sm"))
    verify_parser.add_argument("--compiler", type=Path)
    resolve_parser = subparsers.add_parser("resolve")
    resolve_parser.add_argument("--variant", choices=("ecc", "sm"), required=True)
    return parser.parse_args()


def main() -> int:
    """Execute the selected fail-closed toolchain operation."""
    args = parse_args()
    try:
        if args.command == "provision":
            resolved = provision(args.cache_dir, args.manifest, args.source_dir)
            print(json.dumps({key: str(value) for key, value in resolved.items()}))
        elif args.command == "verify":
            if args.compiler is not None and args.variant is None:
                raise ProvisionError("--compiler requires --variant")
            resolved = verify_release(
                args.cache_dir, args.manifest, args.variant, args.compiler
            )
            print(json.dumps({key: str(value) for key, value in resolved.items()}))
        else:
            resolved = verify_release(args.cache_dir, args.manifest, args.variant)
            print(resolved[args.variant])
        return 0
    except (OSError, ValueError, json.JSONDecodeError, ProvisionError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
