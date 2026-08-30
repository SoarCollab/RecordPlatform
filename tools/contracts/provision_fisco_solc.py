#!/usr/bin/env python3
"""Provision and verify the exact FISCO Solidity compiler pair."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
from contextlib import ExitStack, contextmanager
from pathlib import Path
from typing import Any, Iterator


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_MANIFEST = SCRIPT_DIR / "fisco-solc-toolchains.json"
METADATA_NAME = "provenance.json"
MAX_COMPILER_BYTES = 256 * 1024 * 1024


class ProvisionError(RuntimeError):
    """Raised when compiler provenance or cache integrity is invalid."""


def absolute_path(path: Path) -> Path:
    """Normalize only root-owned macOS OS aliases, never cache symlinks or '..'."""
    path = path.absolute()
    if ".." in path.parts:
        raise ProvisionError(f"parent traversal is forbidden: {path}")
    if sys.platform == "darwin" and len(path.parts) > 1:
        alias = Path("/") / path.parts[1]
        if alias in (Path("/tmp"), Path("/var")) and alias.is_symlink():
            info = alias.lstat()
            destination = Path("/private") / alias.name
            if info.st_uid != 0 or os.readlink(alias) not in (
                str(destination),
                str(destination)[1:],
            ):
                raise ProvisionError(f"untrusted system alias: {alias}")
            path = destination.joinpath(*path.parts[2:])
    return path


def identity(info: os.stat_result) -> tuple[int, int]:
    """Identify an opened filesystem object independently of its pathname."""
    return info.st_dev, info.st_ino


class Directory:
    """Hold every ancestor open and reject redirected or replaced directory entries."""

    def __init__(self, path: Path, *, create: bool = False) -> None:
        """Traverse with no-follow directory descriptors, optionally creating parents."""
        self.path = absolute_path(path)
        self.handles: list[int] = []
        self.names = self.path.parts[1:]
        try:
            self.handles.append(os.open("/", os.O_RDONLY | os.O_DIRECTORY))
            for name in self.names:
                flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
                try:
                    child = os.open(name, flags, dir_fd=self.handles[-1])
                except FileNotFoundError:
                    if not create:
                        raise
                    try:
                        os.mkdir(name, mode=0o700, dir_fd=self.handles[-1])
                    except FileExistsError:
                        pass
                    child = os.open(name, flags, dir_fd=self.handles[-1])
                self.handles.append(child)
            self.check()
        except OSError as error:
            self.close()
            raise ProvisionError(
                f"expected a non-symlink directory: {self.path}"
            ) from error
        except BaseException:
            self.close()
            raise

    @property
    def fd(self) -> int:
        """Return the pinned leaf descriptor."""
        return self.handles[-1]

    def check(self) -> None:
        """Require all names still to designate the captured directory identities."""
        for parent, child, name in zip(self.handles, self.handles[1:], self.names):
            current = os.stat(name, dir_fd=parent, follow_symlinks=False)
            if not stat.S_ISDIR(current.st_mode) or identity(current) != identity(
                os.fstat(child)
            ):
                raise ProvisionError(f"directory identity changed: {self.path}")

    def close(self) -> None:
        """Release descriptors without deleting any mutable cache entry."""
        for handle in reversed(self.handles):
            os.close(handle)
        self.handles.clear()

    def __enter__(self) -> Directory:
        """Use pinned directory state as a context manager."""
        return self

    def __exit__(self, *_: object) -> None:
        """Always close all pinned ancestors."""
        self.close()


def capture_file(
    path: Path, *, executable: bool = False, limit: int = MAX_COMPILER_BYTES
) -> bytes:
    """Capture bounded regular-file bytes through pinned parents and a no-follow FD."""
    with Directory(path.parent) as parent:
        try:
            fd = os.open(
                path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent.fd
            )
        except OSError as error:
            raise ProvisionError(
                f"expected a regular non-symlink file: {path}"
            ) from error
        info = os.fstat(fd)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_size > limit
            or (executable and not info.st_mode & 0o111)
        ):
            os.close(fd)
            raise ProvisionError(
                f"expected a bounded regular non-symlink executable file: {path}"
            )
        with os.fdopen(fd, "rb") as source:
            data = source.read(limit + 1)
            after = os.fstat(source.fileno())
            current = os.stat(path.name, dir_fd=parent.fd, follow_symlinks=False)
            if (
                len(data) > limit
                or identity(info) != identity(current)
                or not stat.S_ISREG(current.st_mode)
                or (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                != (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
            ):
                raise ProvisionError(
                    f"file changed during capture or exceeds size limit: {path}"
                )
            parent.check()
            return data


@contextmanager
def private_directory(parent: Path) -> Iterator[Path]:
    """Keep private state outside the cache; refuse cleanup of a substituted path."""
    with Directory(parent) as boundary:
        temporary = Path(
            tempfile.mkdtemp(prefix=".record-platform-solc-", dir=boundary.path)
        )
        with Directory(temporary) as owned:
            try:
                boundary.check()
                yield temporary
            finally:
                # Only private executor state is recursively cleaned, never a cache target.
                # Arbitrary access to this state by the same UID is outside the threat model.
                owned.check()
                shutil.rmtree(temporary)


@contextmanager
def executable_snapshot(path: Path, expected_sha: str, private: Path) -> Iterator[Path]:
    """Hash the exact private bytes used by version checking and compilation."""
    data = capture_file(path, executable=True)
    if hashlib.sha256(data).hexdigest() != expected_sha:
        raise ProvisionError(f"compiler SHA-256 mismatch: {path}")
    with private_directory(private) as snapshot_dir:
        snapshot = snapshot_dir / "solc"
        with snapshot.open("xb") as output:
            output.write(data)
        snapshot.chmod(0o500)
        # Verify the copied bytes as well; the mutable cache is never executed.
        if sha256_file(snapshot) != expected_sha:
            raise ProvisionError("private compiler snapshot SHA-256 mismatch")
        yield snapshot


def publish_noreplace(staging: Path, cache: Directory, name: str) -> None:
    """Atomically publish via pinned parents without replacing any competing entry."""
    with Directory(staging.parent) as source:
        source.check()
        cache.check()
        libc = ctypes.CDLL(None, use_errno=True)
        if sys.platform == "linux":
            function = getattr(libc, "renameat2", None)
            flags = 1  # RENAME_NOREPLACE
        elif sys.platform == "darwin":
            function = getattr(libc, "renameatx_np", None)
            flags = 4  # RENAME_EXCL (local fixture tests; real builders are Linux)
        else:
            function = None
        if function is None:
            raise ProvisionError(
                "atomic no-replace directory publication is unavailable"
            )
        function.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        function.restype = ctypes.c_int
        if function(
            source.fd, os.fsencode(staging.name), cache.fd, os.fsencode(name), flags
        ):
            error = ctypes.get_errno()
            raise OSError(error, os.strerror(error), str(cache.path / name))


def execution_temp_parent(cache: Path) -> Path:
    """Use executor temporary storage outside the mutable cache, including read-only caches."""
    temporary = absolute_path(Path(tempfile.gettempdir()))
    if temporary == cache or cache in temporary.parents:
        raise ProvisionError(
            "executor temporary directory must be outside the compiler cache"
        )
    return temporary


def sha256_file(path: Path) -> str:
    """Return the lowercase SHA-256 digest of a regular non-symlink file."""
    return hashlib.sha256(capture_file(path)).hexdigest()


def load_manifest(path: Path) -> tuple[dict[str, Any], str]:
    """Load the reviewed manifest and return it with its exact digest."""
    raw = capture_file(path, limit=1024 * 1024)
    manifest = json.loads(raw)
    if manifest.get("schemaVersion") != "record-platform-fisco-solc-toolchains.v1":
        raise ProvisionError("unsupported FISCO solc toolchain manifest schema")
    if set(manifest.get("variants", {})) != {"ecc", "sm"}:
        raise ProvisionError("manifest must define exactly ecc and sm variants")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("sourceCommit", "")):
        raise ProvisionError(
            "manifest sourceCommit must be a full lowercase Git commit"
        )
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


@contextmanager
def verified_release(
    target: Path,
    manifest: dict[str, Any],
    manifest_sha: str,
    private: Path,
    requested_variant: str | None = None,
    requested_compiler: Path | None = None,
) -> Iterator[dict[str, Path]]:
    """Validate provenance and yield executor-owned snapshots, never cache executables."""
    metadata = json.loads(capture_file(target / METADATA_NAME, limit=1024 * 1024))
    required_metadata = {
        "schemaVersion": "record-platform-fisco-solc-provenance.v1",
        "manifestSha256": manifest_sha,
        "sourceCommit": manifest["sourceCommit"],
        "releasePrereleaseMarker": "empty",
        "sourceRepository": manifest["sourceRepository"],
        "platform": validate_platform(manifest),
    }
    for key, expected in required_metadata.items():
        if metadata.get(key) != expected:
            raise ProvisionError(f"compiler provenance mismatch for {key}")

    variants = [requested_variant] if requested_variant else ["ecc", "sm"]
    with Directory(target) as boundary, ExitStack() as snapshots:
        resolved: dict[str, Path] = {}
        for variant in variants:
            if variant not in manifest["variants"]:
                raise ProvisionError(f"unsupported compiler variant: {variant}")
            compiler = target / variant / "solc"
            if requested_compiler is not None and absolute_path(
                requested_compiler
            ) != absolute_path(compiler):
                raise ProvisionError(
                    "explicit compiler path is outside the verified cache release"
                )
            expected_sha = manifest["variants"][variant]["validatedExecutableSha256"]
            variant_metadata = metadata.get("variants", {}).get(variant, {})
            if variant_metadata.get("sha256") != expected_sha:
                raise ProvisionError(
                    f"{variant} provenance executable SHA-256 mismatch"
                )
            for key in ("buildGm", "versionLine"):
                if variant_metadata.get(key) != manifest["variants"][variant][key]:
                    raise ProvisionError(f"{variant} provenance mismatch for {key}")
            resolved[variant] = snapshots.enter_context(
                executable_snapshot(compiler, expected_sha, private)
            )
        boundary.check()
        for variant, snapshot in resolved.items():
            verify_version(snapshot, manifest["variants"][variant]["versionLine"])
        yield resolved


def verify_release(
    cache_dir: Path,
    manifest_path: Path,
    requested_variant: str | None = None,
    requested_compiler: Path | None = None,
) -> dict[str, Path]:
    """Verify an offline cache; returned paths are diagnostics, not execution authority."""
    manifest, manifest_sha = load_manifest(manifest_path)
    validate_platform(manifest)
    with Directory(cache_dir) as cache:
        target = release_dir(cache.path, manifest)
        with private_directory(execution_temp_parent(cache.path)) as private:
            with verified_release(
                target,
                manifest,
                manifest_sha,
                private,
                requested_variant,
                requested_compiler,
            ) as snapshots:
                return {variant: target / variant / "solc" for variant in snapshots}


def run_compiler(
    cache_dir: Path,
    manifest_path: Path,
    variant: str,
    arguments: list[str],
    requested_compiler: Path | None = None,
) -> int:
    """Forward arguments and stdio using the same verified private executable snapshot."""
    manifest, manifest_sha = load_manifest(manifest_path)
    validate_platform(manifest)
    with Directory(cache_dir) as cache, private_directory(
        execution_temp_parent(cache.path)
    ) as private:
        with verified_release(
            release_dir(cache.path, manifest),
            manifest,
            manifest_sha,
            private,
            variant,
            requested_compiler,
        ) as snapshots:
            return subprocess.run(
                [str(snapshots[variant]), *arguments], check=False
            ).returncode


def checkout_source(source_dir: Path, manifest: dict[str, Any], work_dir: Path) -> Path:
    """Copy an exact Git commit into an isolated release-mode source tree."""
    if source_dir.is_symlink() or not source_dir.is_dir():
        raise ProvisionError(
            f"source directory must be a non-symlink directory: {source_dir}"
        )
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


def build_variant(
    source: Path, work_dir: Path, variant: str, manifest: dict[str, Any]
) -> Path:
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
    expected_sha = manifest["variants"][variant]["validatedExecutableSha256"]
    with executable_snapshot(compiler, expected_sha, work_dir) as snapshot:
        verify_version(snapshot, manifest["variants"][variant]["versionLine"])
    return compiler


def provision(
    cache_dir: Path, manifest_path: Path, source_dir: Path | None
) -> dict[str, Path]:
    """Build both exact compilers and atomically publish a complete cache release."""
    manifest, manifest_sha = load_manifest(manifest_path)
    platform_contract = validate_platform(manifest)
    with Directory(cache_dir, create=True) as cache:
        target = release_dir(cache.path, manifest)
        try:
            os.stat(target.name, dir_fd=cache.fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            return verify_release(cache.path, manifest_path)
        # A sibling private workspace is on the publication filesystem but outside
        # the mutable cache. Cache writers cannot alter staged bytes or snapshots.
        with private_directory(cache.path.parent) as work_dir:
            return provision_private(
                cache,
                target,
                manifest,
                manifest_sha,
                platform_contract,
                source_dir,
                work_dir,
            )


def provision_private(
    cache: Directory,
    target: Path,
    manifest: dict[str, Any],
    manifest_sha: str,
    platform_contract: dict[str, Any],
    source_dir: Path | None,
    work_dir: Path,
) -> dict[str, Path]:
    """Build and validate the whole private staging tree before a single publication."""
    if source_dir is None:
        source_dir = work_dir / "checkout"
        run(
            [
                "git",
                "clone",
                "--filter=blob:none",
                "--no-checkout",
                manifest["sourceRepository"],
                str(source_dir),
            ]
        )
        run(
            [
                "git",
                "-C",
                str(source_dir),
                "checkout",
                "--detach",
                manifest["sourceCommit"],
            ]
        )
    source = checkout_source(source_dir, manifest, work_dir)
    download_source_dependencies(source, manifest)
    built: dict[str, Path] = {}
    for variant in manifest["releaseBuild"]["buildOrder"]:
        built[variant] = build_variant(source, work_dir, variant, manifest)

    staging = work_dir / "release"
    staging.mkdir(mode=0o700)
    variant_metadata: dict[str, Any] = {}
    for variant in ("ecc", "sm"):
        destination_dir = staging / variant
        destination_dir.mkdir(mode=0o700)
        destination = destination_dir / "solc"
        destination.write_bytes(capture_file(built[variant], executable=True))
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
    with verified_release(staging, manifest, manifest_sha, work_dir):
        try:
            publish_noreplace(staging, cache, target.name)
        except FileExistsError:
            # A concurrent publisher wins. Never overwrite or clean its target.
            with verified_release(target, manifest, manifest_sha, work_dir):
                pass
    return {variant: target / variant / "solc" for variant in ("ecc", "sm")}


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
    run_parser = subparsers.add_parser("run")
    run_parser.add_argument("--variant", choices=("ecc", "sm"), required=True)
    run_parser.add_argument("--compiler", type=Path)
    run_parser.add_argument("arguments", nargs=argparse.REMAINDER)
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
        elif args.command == "resolve":
            resolved = verify_release(args.cache_dir, args.manifest, args.variant)
            print(resolved[args.variant])
        else:
            arguments = args.arguments
            if arguments[:1] == ["--"]:
                arguments = arguments[1:]
            return run_compiler(
                args.cache_dir, args.manifest, args.variant, arguments, args.compiler
            )
        return 0
    except (OSError, ValueError, json.JSONDecodeError, ProvisionError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
