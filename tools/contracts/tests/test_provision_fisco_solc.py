"""Tests for exact FISCO Solidity compiler cache verification."""

from __future__ import annotations

import hashlib
import io
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from contextlib import ExitStack, contextmanager

from tools.contracts import provision_fisco_solc as solc

from tools.contracts.provision_fisco_solc import (
    ProvisionError,
    checkout_source,
    download_source_dependencies,
    verify_release,
)


SOURCE_COMMIT = "6b4cc280eb884f1852e1ddf12aeea56a2103148c"


class ProvisionFiscoSolcTest(unittest.TestCase):
    """Cover provenance, platform, path, mode, version and digest failures."""

    def test_all_cache_directory_symlinks_fail_without_execution(self) -> None:
        """Root, ancestor, release and variant links are rejected before any execution."""
        for component in ("ancestor", "cache", "release", "ecc", "sm"):
            with self.subTest(
                component=component
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                holder = root / "holder"
                holder.mkdir()
                manifest, cache = self._fixture(holder)
                # Keep the trusted manifest outside the redirected cache ancestor.
                manifest = manifest.replace(root / "manifest.json")
                targets = {
                    "ancestor": holder,
                    "cache": cache,
                    "release": cache / SOURCE_COMMIT,
                    "ecc": cache / SOURCE_COMMIT / "ecc",
                    "sm": cache / SOURCE_COMMIT / "sm",
                }
                target = targets[component]
                moved = target.replace(root / "external")
                target.symlink_to(moved, target_is_directory=True)
                with patch.object(solc, "run") as execute:
                    with self.assertRaises(ProvisionError):
                        verify_release(cache, manifest)
                    execute.assert_not_called()

    def test_metadata_symlink_and_nonregular_compiler_are_rejected(self) -> None:
        """Metadata links, FIFO compilers and directories cannot be consumed or executed."""
        for kind in ("metadata-link", "fifo", "directory", "not-executable"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                manifest, cache = self._fixture(root)
                path = (
                    cache
                    / SOURCE_COMMIT
                    / ("provenance.json" if kind == "metadata-link" else "ecc/solc")
                )
                if kind == "not-executable":
                    path.chmod(0o600)
                else:
                    moved = path.replace(root / "original")
                    if kind == "metadata-link":
                        path.symlink_to(moved)
                    elif kind == "fifo":
                        os.mkfifo(path)
                    else:
                        path.mkdir()
                with patch.object(solc, "run") as execute:
                    with self.assertRaises(ProvisionError):
                        verify_release(cache, manifest)
                    execute.assert_not_called()

    def test_hash_to_version_and_compile_replacement_cannot_execute_cache(self) -> None:
        """Atomic replacement and in-place writes after snapshot hash never change execution."""
        for operation in ("verify", "run"):
            for mutation in ("replace", "rewrite"):
                with self.subTest(
                    operation=operation, mutation=mutation
                ), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary).resolve()
                    manifest, cache = self._fixture(root)
                    compiler = cache / SOURCE_COMMIT / "ecc/solc"
                    marker = root / "unverified-executed"
                    payload = f"#!/bin/sh\ntouch '{marker}'\nprintf '%s\\n' 'Version: 0.8.11+commit.6b4cc280.Linux.g++'\n"
                    hash_file = solc.sha256_file
                    changed = False

                    def swap_after_hash(path: Path) -> str:
                        """Inject a cache mutation at the exact validated-snapshot hash boundary."""
                        nonlocal changed
                        digest = hash_file(path)
                        if path.name == "solc" and not changed:
                            changed = True
                            if mutation == "replace":
                                replacement = root / "replacement"
                                replacement.write_text(payload)
                                replacement.chmod(0o700)
                                replacement.replace(compiler)
                            else:
                                compiler.write_text(payload)
                        return digest

                    with patch.object(solc, "sha256_file", side_effect=swap_after_hash):
                        if operation == "verify":
                            verify_release(cache, manifest, "ecc")
                        else:
                            self.assertEqual(
                                solc.run_compiler(
                                    cache, manifest, "ecc", ["--version"]
                                ),
                                0,
                            )
                    self.assertTrue(changed)
                    self.assertFalse(marker.exists())
                    with self.assertRaisesRegex(ProvisionError, "SHA-256 mismatch"):
                        verify_release(cache, manifest, "ecc")

    def test_replacement_between_version_and_compile_uses_same_snapshot(self) -> None:
        """The run entrypoint retains its verified snapshot across the second execution."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            marker = root / "marker"
            original = solc.verify_version

            def mutate_after_version(path: Path, version: str) -> str:
                """Replace the cache only after a real snapshot version invocation."""
                result = original(path, version)
                (cache / SOURCE_COMMIT / "ecc/solc").write_text(
                    f"#!/bin/sh\ntouch '{marker}'\n"
                )
                return result

            with patch.object(solc, "verify_version", side_effect=mutate_after_version):
                self.assertEqual(
                    solc.run_compiler(cache, manifest, "ecc", ["input.sol"]), 0
                )
            self.assertFalse(marker.exists())

    def test_directory_swap_during_capture_is_rejected(self) -> None:
        """Pinned ancestors detect a variant directory swapped after the file was opened."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            variant = cache / SOURCE_COMMIT / "ecc"
            fdopen = solc.os.fdopen
            changed = False

            def swap_parent(fd: int, mode: str):
                """Swap the parent just before reading the captured regular descriptor."""
                nonlocal changed
                if (
                    identity_for_test(fd) == solc.identity((variant / "solc").stat())
                    and not changed
                ):
                    changed = True
                    variant.replace(root / "moved")
                    variant.symlink_to(root / "moved", target_is_directory=True)
                return fdopen(fd, mode)

            def identity_for_test(fd: int) -> tuple[int, int]:
                """Read the identity of a fixture file descriptor."""
                return solc.identity(os.fstat(fd))

            with patch.object(solc.os, "fdopen", side_effect=swap_parent), patch.object(
                solc, "run"
            ) as execute:
                with self.assertRaisesRegex(
                    ProvisionError, "directory identity changed"
                ):
                    verify_release(cache, manifest, "ecc")
                execute.assert_not_called()

    def test_inplace_write_during_capture_is_rejected(self) -> None:
        """A deterministic write after opening but before reading cannot authorize bytes."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            compiler = cache / SOURCE_COMMIT / "ecc/solc"
            original = solc.os.fstat
            changed = False

            def rewrite_after_stat(fd: int):
                """Change the captured inode immediately after its initial metadata sample."""
                nonlocal changed
                result = original(fd)
                if (
                    solc.identity(result) == solc.identity(compiler.stat())
                    and not changed
                ):
                    changed = True
                    compiler.write_bytes(b"unverified")
                return result

            with patch.object(
                solc.os, "fstat", side_effect=rewrite_after_stat
            ), patch.object(solc, "run") as execute:
                with self.assertRaisesRegex(
                    ProvisionError, "file changed during capture"
                ):
                    verify_release(cache, manifest, "ecc")
                execute.assert_not_called()

    def test_build_hash_is_checked_before_version_execution(self) -> None:
        """The early build-output path must not execute a digest-mismatched binary."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest_path, _ = self._fixture(root)
            compiler = root / "build-ecc/solc/solc"
            compiler.parent.mkdir(parents=True)
            compiler.write_text("#!/bin/sh\nexit 0\n")
            compiler.chmod(0o700)
            with patch.object(solc, "run") as execute:
                with self.assertRaisesRegex(ProvisionError, "SHA-256 mismatch"):
                    solc.build_variant(
                        root, root, "ecc", json.loads(manifest_path.read_text())
                    )
            self.assertEqual(execute.call_count, 3)
            self.assertTrue(
                all(call.args[0][0] == "cmake" for call in execute.call_args_list)
            )

    @contextmanager
    def _fresh_build(self, root: Path):
        """Supply deterministic built compiler bytes without downloading or running CMake."""
        manifest, cache = self._fixture(root)
        built = (cache / SOURCE_COMMIT).replace(root / "built")
        with patch.object(solc, "checkout_source", return_value=root), patch.object(
            solc, "download_source_dependencies"
        ), patch.object(
            solc,
            "build_variant",
            side_effect=lambda source, work, variant, data: built / variant / "solc",
        ):
            yield manifest, cache, built

    def test_fresh_publish_and_offline_reuse_preserve_both_variants(self) -> None:
        """Complete staging publishes once and later offline reuse invokes no build tools."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with self._fresh_build(root) as (manifest, cache, _):
                result = solc.provision(cache, manifest, root)
                self.assertEqual(set(result), {"ecc", "sm"})
                self.assertEqual(set(cache.iterdir()), {cache / SOURCE_COMMIT})
                with patch.object(solc, "checkout_source") as checkout, patch.object(
                    solc, "download_source_dependencies"
                ) as download, patch.object(solc, "build_variant") as build:
                    self.assertEqual(solc.provision(cache, manifest, None), result)
                    checkout.assert_not_called()
                    download.assert_not_called()
                    build.assert_not_called()

    def test_build_copy_metadata_version_and_publication_failures_allow_retry(
        self,
    ) -> None:
        """Each prepublication failure leaves no official release and no poisoned retry."""
        for failure in (
            "build",
            "copy",
            "copy-corruption",
            "metadata",
            "version",
            "publication",
        ):
            with self.subTest(
                failure=failure
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                with self._fresh_build(root) as (manifest, cache, _):
                    write_bytes = Path.write_bytes
                    write_text = Path.write_text

                    def copy(path: Path, data: bytes) -> int:
                        """Inject only at the staged compiler copy boundary."""
                        if (
                            path.parent.name == "ecc"
                            and path.parent.parent.name == "release"
                        ):
                            if failure == "copy":
                                raise OSError("copy failure")
                            if failure == "copy-corruption":
                                data = b"unverified bytes"
                        return write_bytes(path, data)

                    def metadata(path: Path, data: str, **kwargs) -> int:
                        """Inject a provenance write error before publication."""
                        if failure == "metadata" and path.name == solc.METADATA_NAME:
                            raise OSError("metadata failure")
                        return write_text(path, data, **kwargs)

                    with patch.object(Path, "write_bytes", copy), patch.object(
                        Path, "write_text", metadata
                    ):
                        with ExitStack() as stack:
                            if failure in ("build", "version", "publication"):
                                method = {
                                    "build": "build_variant",
                                    "version": "verify_version",
                                    "publication": "publish_noreplace",
                                }[failure]
                                stack.enter_context(
                                    patch.object(
                                        solc,
                                        method,
                                        side_effect=ProvisionError(failure),
                                    )
                                )
                            with self.assertRaises((OSError, ProvisionError)):
                                solc.provision(cache, manifest, root)
                    self.assertFalse((cache / SOURCE_COMMIT).exists())
                    self.assertFalse(list(root.glob(".record-platform-solc-*")))
                    self.assertEqual(
                        set(solc.provision(cache, manifest, root)), {"ecc", "sm"}
                    )

    def test_postbuild_rewrite_is_rejected_before_publication(self) -> None:
        """An earlier valid build result corrupted while the other variant builds is rejected."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with self._fresh_build(root) as (manifest, cache, built):
                original = (built / "sm/solc").read_bytes()

                def build(source, work, variant, data):
                    """Corrupt the first build after its early verification would have passed."""
                    if variant == "ecc":
                        (built / "sm/solc").write_bytes(b"bad build output")
                    return built / variant / "solc"

                with patch.object(solc, "build_variant", side_effect=build):
                    with self.assertRaisesRegex(ProvisionError, "SHA-256 mismatch"):
                        solc.provision(cache, manifest, root)
                self.assertFalse((cache / SOURCE_COMMIT).exists())
                (built / "sm/solc").write_bytes(original)
                solc.provision(cache, manifest, root)

    def test_competing_target_is_never_overwritten(self) -> None:
        """Atomic publication preserves both valid competitors and invalid/empty targets."""
        for valid in (True, False):
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                with self._fresh_build(root) as (manifest, cache, built):
                    publish = solc.publish_noreplace
                    target = cache / SOURCE_COMMIT
                    target_identity = None

                    def competitor(staging, pinned_cache, name):
                        """Publish a competitor at the last moment before the real atomic syscall."""
                        nonlocal target_identity
                        if valid:
                            shutil.copytree(built, target)
                        else:
                            target.mkdir()
                        target_identity = solc.identity(target.stat())
                        return publish(staging, pinned_cache, name)

                    with patch.object(
                        solc, "publish_noreplace", side_effect=competitor
                    ):
                        if valid:
                            solc.provision(cache, manifest, root)
                        else:
                            with self.assertRaises((ProvisionError, OSError)):
                                solc.provision(cache, manifest, root)
                    self.assertEqual(solc.identity(target.stat()), target_identity)
                    if valid:
                        verify_release(cache, manifest)
                    else:
                        self.assertEqual(list(target.iterdir()), [])

    def test_cache_swap_before_publication_cannot_touch_external_directory(
        self,
    ) -> None:
        """A substituted cache root is rejected while the destination remains untouched."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with self._fresh_build(root) as (manifest, cache, _):
                external = root / "external"
                external.mkdir()
                publish = solc.publish_noreplace

                def swap(staging, pinned_cache, name):
                    """Redirect the cache immediately before publication boundary validation."""
                    cache.replace(root / "old-cache")
                    cache.symlink_to(external, target_is_directory=True)
                    return publish(staging, pinned_cache, name)

                with patch.object(solc, "publish_noreplace", side_effect=swap):
                    with self.assertRaisesRegex(
                        ProvisionError, "directory identity changed"
                    ):
                        solc.provision(cache, manifest, root)
                self.assertEqual(list(external.iterdir()), [])
                self.assertEqual(list((root / "old-cache").iterdir()), [])

    def test_cleanup_refuses_substituted_private_workspace(self) -> None:
        """Cleanup must never recursively remove an entry that lost its owned identity."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with self.assertRaisesRegex(ProvisionError, "directory identity changed"):
                with solc.private_directory(root) as private:
                    private.replace(root / "original")
                    private.mkdir()
                    marker = private / "competitor"
                    marker.write_text("preserve")
            self.assertEqual(marker.read_text(), "preserve")

    def test_all_cli_operations_are_offline_and_run_preserves_io_and_status(
        self,
    ) -> None:
        """Run preserves literal arguments, cwd, stdin, stdout, stderr and nonzero exit status."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            compiler = cache / SOURCE_COMMIT / "ecc/solc"
            compiler.write_text(
                "#!/bin/sh\nif [ \"$1\" = --version ]; then\nprintf '%s\\n' 'Version: 0.8.11+commit.6b4cc280.Linux.g++'\nelse\nprintf '%s\\n' \"$1\"; pwd; cat; printf 'stderr sentinel\\n' >&2; exit 7\nfi\n"
            )
            digest = hashlib.sha256(compiler.read_bytes()).hexdigest()
            data = json.loads(manifest.read_text())
            data["variants"]["ecc"]["validatedExecutableSha256"] = digest
            manifest.write_text(json.dumps(data))
            provenance = cache / SOURCE_COMMIT / solc.METADATA_NAME
            data = json.loads(provenance.read_text())
            data["variants"]["ecc"]["sha256"] = digest
            data["manifestSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
            provenance.write_text(json.dumps(data))
            base = [
                sys.executable,
                str(Path(solc.__file__).resolve()),
                "--manifest",
                str(manifest),
                "--cache-dir",
                str(cache),
            ]
            for args in (["provision"], ["verify"], ["resolve", "--variant", "sm"]):
                result = subprocess.run(
                    base + args, cwd=root, text=True, capture_output=True
                )
                self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(
                base
                + [
                    "run",
                    "--variant",
                    "ecc",
                    "--compiler",
                    str(compiler),
                    "--",
                    "literal $value; argument",
                ],
                cwd=root,
                input="stdin sentinel\n",
                text=True,
                capture_output=True,
            )
            self.assertEqual(result.returncode, 7)
            self.assertEqual(
                result.stdout, f"literal $value; argument\n{root}\nstdin sentinel\n"
            )
            self.assertEqual(result.stderr, "stderr sentinel\n")

    def test_readonly_cache_can_be_verified_and_run(self) -> None:
        """Offline operations write private snapshots outside a read-only cache hierarchy."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            directories = [
                root,
                cache,
                cache / SOURCE_COMMIT,
                cache / SOURCE_COMMIT / "ecc",
                cache / SOURCE_COMMIT / "sm",
            ]
            for directory in directories:
                directory.chmod(0o500)
            try:
                self.assertEqual(set(verify_release(cache, manifest)), {"ecc", "sm"})
                self.assertEqual(
                    set(solc.provision(cache, manifest, None)), {"ecc", "sm"}
                )
                self.assertEqual(
                    solc.run_compiler(cache, manifest, "sm", ["--version"]), 0
                )
            finally:
                for directory in directories:
                    directory.chmod(0o700)

    def test_temporary_storage_inside_cache_is_rejected(self) -> None:
        """A TMPDIR inside attacker-controlled cache state cannot host executable snapshots."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            manifest, cache = self._fixture(root)
            with patch.object(
                solc.tempfile, "gettempdir", return_value=str(cache)
            ), patch.object(solc, "run") as execute:
                with self.assertRaisesRegex(
                    ProvisionError, "outside the compiler cache"
                ):
                    verify_release(cache, manifest)
                execute.assert_not_called()

    def test_deployment_and_ci_never_execute_resolved_cache_paths(self) -> None:
        """Both production consumers use the safe run boundary rather than raw path execution."""
        project = Path(solc.__file__).resolve().parents[2]
        deploy = (project / "scripts/contract-deploy.sh").read_text()
        workflow = (project / ".github/workflows/contract-security.yml").read_text()
        self.assertNotIn('"$compiler" --version', deploy)
        self.assertNotIn('\n        "$compiler" \\', deploy)
        self.assertNotIn('\n              "$compiler" \\', workflow)
        self.assertIn('--cache-dir "$SOLC_CACHE_DIR" run', deploy)
        self.assertIn('run --variant "$variant" --', workflow)

    def test_verified_offline_cache_resolves_both_variants(self) -> None:
        """A complete cache with matching metadata is reusable without a build."""
        with tempfile.TemporaryDirectory() as temporary:
            manifest, cache = self._fixture(Path(temporary))

            resolved = verify_release(cache, manifest)

            self.assertEqual(set(resolved), {"ecc", "sm"})
            self.assertTrue(all(path.is_file() for path in resolved.values()))

    def test_wrong_executable_digest_is_rejected(self) -> None:
        """Replacing a compiler after publication invalidates the cache."""
        with tempfile.TemporaryDirectory() as temporary:
            manifest, cache = self._fixture(Path(temporary))
            (cache / SOURCE_COMMIT / "ecc/solc").write_text("replaced\n")

            with self.assertRaisesRegex(ProvisionError, "SHA-256 mismatch"):
                verify_release(cache, manifest)

    def test_symlinked_compiler_is_rejected(self) -> None:
        """A cache compiler may never redirect through a symbolic link."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, cache = self._fixture(root)
            compiler = cache / SOURCE_COMMIT / "sm/solc"
            original = root / "original-solc"
            compiler.replace(original)
            compiler.symlink_to(original)

            with self.assertRaisesRegex(ProvisionError, "regular non-symlink"):
                verify_release(cache, manifest)

    def test_wrong_source_commit_metadata_is_rejected(self) -> None:
        """Cache metadata cannot claim a different source checkout."""
        with tempfile.TemporaryDirectory() as temporary:
            manifest, cache = self._fixture(Path(temporary))
            metadata_path = cache / SOURCE_COMMIT / "provenance.json"
            metadata = json.loads(metadata_path.read_text())
            metadata["sourceCommit"] = "0" * 40
            metadata_path.write_text(json.dumps(metadata))

            with self.assertRaisesRegex(ProvisionError, "sourceCommit"):
                verify_release(cache, manifest)

    def test_wrong_source_checkout_is_rejected_before_export(self) -> None:
        """Provisioning never builds a checkout whose HEAD differs from the manifest."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_path, _ = self._fixture(root)
            manifest = json.loads(manifest_path.read_text())
            source = root / "source"
            source.mkdir()
            subprocess.run(["git", "init", "-q"], cwd=source, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=source,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=source, check=True
            )
            (source / "README").write_text("fixture\n")
            subprocess.run(["git", "add", "README"], cwd=source, check=True)
            subprocess.run(["git", "commit", "-qm", "fixture"], cwd=source, check=True)

            with self.assertRaisesRegex(ProvisionError, "source commit mismatch"):
                checkout_source(source, manifest, root / "work")

    def test_reviewed_dependency_is_downloaded_and_verified(self) -> None:
        """Explicit provisioning stages only dependency bytes matching the manifest."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"reviewed archive bytes"
            manifest = {
                "sourceDependencies": [
                    {
                        "name": "fixture",
                        "url": "https://example.invalid/fixture.tar.gz",
                        "path": "deps/downloads/fixture.tar.gz",
                        "sha256": hashlib.sha256(payload).hexdigest(),
                    }
                ]
            }
            with patch(
                "tools.contracts.provision_fisco_solc.urllib.request.urlopen",
                return_value=io.BytesIO(payload),
            ):
                download_source_dependencies(root, manifest)

            self.assertEqual(
                (root / "deps/downloads/fixture.tar.gz").read_bytes(), payload
            )

    def test_dependency_digest_mismatch_leaves_no_staged_archive(self) -> None:
        """Wrong upstream bytes fail before CMake and leave no trusted file."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = {
                "sourceDependencies": [
                    {
                        "name": "fixture",
                        "url": "https://example.invalid/fixture.tar.gz",
                        "path": "deps/downloads/fixture.tar.gz",
                        "sha256": "0" * 64,
                    }
                ]
            }
            with patch(
                "tools.contracts.provision_fisco_solc.urllib.request.urlopen",
                return_value=io.BytesIO(b"wrong"),
            ):
                with self.assertRaisesRegex(ProvisionError, "SHA-256 mismatch"):
                    download_source_dependencies(root, manifest)

            self.assertFalse((root / "deps/downloads/fixture.tar.gz").exists())

    def test_wrong_version_line_is_rejected_even_with_matching_digest(self) -> None:
        """A digest-consistent binary still needs the exact release identity."""
        with tempfile.TemporaryDirectory() as temporary:
            manifest, cache = self._fixture(Path(temporary))
            compiler = cache / SOURCE_COMMIT / "ecc/solc"
            compiler.write_text("#!/bin/sh\nprintf '%s\\n' 'Version: 0.8.11-develop'\n")
            compiler.chmod(0o700)
            digest = hashlib.sha256(compiler.read_bytes()).hexdigest()
            manifest_data = json.loads(manifest.read_text())
            manifest_data["variants"]["ecc"]["validatedExecutableSha256"] = digest
            manifest.write_text(json.dumps(manifest_data))
            metadata_path = cache / SOURCE_COMMIT / "provenance.json"
            metadata = json.loads(metadata_path.read_text())
            metadata["manifestSha256"] = hashlib.sha256(
                manifest.read_bytes()
            ).hexdigest()
            metadata["variants"]["ecc"]["sha256"] = digest
            metadata_path.write_text(json.dumps(metadata))

            with self.assertRaisesRegex(ProvisionError, "exact version line"):
                verify_release(cache, manifest)

    def test_wrong_build_mode_metadata_is_rejected(self) -> None:
        """A develop-mode cache cannot stand in for the release-mode toolchain."""
        with tempfile.TemporaryDirectory() as temporary:
            manifest, cache = self._fixture(Path(temporary))
            metadata_path = cache / SOURCE_COMMIT / "provenance.json"
            metadata = json.loads(metadata_path.read_text())
            metadata["variants"]["sm"]["buildGm"] = False
            metadata_path.write_text(json.dumps(metadata))

            with self.assertRaisesRegex(ProvisionError, "buildGm"):
                verify_release(cache, manifest)

    def test_explicit_path_outside_verified_cache_is_rejected(self) -> None:
        """An executable version alone does not establish source provenance."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, cache = self._fixture(root)
            external = root / "external-solc"
            external.write_bytes((cache / SOURCE_COMMIT / "ecc/solc").read_bytes())
            external.chmod(0o700)

            with self.assertRaisesRegex(ProvisionError, "outside the verified cache"):
                verify_release(cache, manifest, "ecc", external)

    def test_unsupported_platform_is_rejected(self) -> None:
        """The manifest platform contract fails closed on an unknown target."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest, cache = self._fixture(root)
            data = json.loads(manifest.read_text())
            data["supportedPlatforms"] = [{"system": "plan9", "machine": "mips"}]
            manifest.write_text(json.dumps(data))

            with self.assertRaisesRegex(ProvisionError, "unsupported build platform"):
                verify_release(cache, manifest)

    def _fixture(self, root: Path) -> tuple[Path, Path]:
        """Create a complete fake cache whose scripts report exact identities."""
        machine = platform.machine().lower()
        if machine in {"amd64", "x64"}:
            machine = "x86_64"
        versions = {
            "ecc": "Version: 0.8.11+commit.6b4cc280.Linux.g++",
            "sm": "Gm version: 0.8.11+commit.6b4cc280.Linux.g++",
        }
        cache = root / "cache"
        release = cache / SOURCE_COMMIT
        shas: dict[str, str] = {}
        for variant, version in versions.items():
            compiler = release / variant / "solc"
            compiler.parent.mkdir(parents=True)
            compiler.write_text(f"#!/bin/sh\nprintf '%s\\n' '{version}'\n")
            compiler.chmod(0o700)
            shas[variant] = hashlib.sha256(compiler.read_bytes()).hexdigest()
        manifest = root / "manifest.json"
        manifest_data = {
            "schemaVersion": "record-platform-fisco-solc-toolchains.v1",
            "sourceRepository": "https://example.invalid/solidity.git",
            "sourceCommit": SOURCE_COMMIT,
            "solidityVersion": "0.8.11",
            "releaseBuild": {
                "prereleaseMarker": "empty",
                "commitHashMarker": SOURCE_COMMIT,
                "cmakeArguments": [],
                "buildOrder": ["sm", "ecc"],
            },
            "supportedPlatforms": [
                {
                    "system": platform.system().lower(),
                    "machine": machine,
                }
            ],
            "variants": {
                variant: {
                    "buildGm": variant == "sm",
                    "versionLine": version,
                    "validatedExecutableSha256": shas[variant],
                }
                for variant, version in versions.items()
            },
        }
        manifest.write_text(json.dumps(manifest_data))
        manifest_sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
        (release / "provenance.json").write_text(
            json.dumps(
                {
                    "schemaVersion": "record-platform-fisco-solc-provenance.v1",
                    "manifestSha256": manifest_sha,
                    "sourceCommit": SOURCE_COMMIT,
                    "releasePrereleaseMarker": "empty",
                    "sourceRepository": manifest_data["sourceRepository"],
                    "platform": manifest_data["supportedPlatforms"][0],
                    "variants": {
                        variant: {
                            "sha256": digest,
                            "buildGm": variant == "sm",
                            "versionLine": versions[variant],
                        }
                        for variant, digest in shas.items()
                    },
                }
            )
        )
        return manifest, cache


if __name__ == "__main__":
    unittest.main()
