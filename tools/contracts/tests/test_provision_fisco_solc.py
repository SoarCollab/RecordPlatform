"""Tests for exact FISCO Solidity compiler cache verification."""

from __future__ import annotations

import hashlib
import json
import platform
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.contracts.provision_fisco_solc import (
    ProvisionError,
    checkout_source,
    verify_release,
)


SOURCE_COMMIT = "6b4cc280eb884f1852e1ddf12aeea56a2103148c"


class ProvisionFiscoSolcTest(unittest.TestCase):
    """Cover provenance, platform, path, mode, version and digest failures."""

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
            metadata["manifestSha256"] = hashlib.sha256(manifest.read_bytes()).hexdigest()
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
            "supportedPlatforms": [{
                "system": platform.system().lower(),
                "machine": machine,
            }],
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
        (release / "provenance.json").write_text(json.dumps({
            "schemaVersion": "record-platform-fisco-solc-provenance.v1",
            "manifestSha256": manifest_sha,
            "sourceCommit": SOURCE_COMMIT,
            "releasePrereleaseMarker": "empty",
            "variants": {
                variant: {
                    "sha256": digest,
                    "buildGm": variant == "sm",
                    "versionLine": versions[variant],
                }
                for variant, digest in shas.items()
            },
        }))
        return manifest, cache


if __name__ == "__main__":
    unittest.main()
