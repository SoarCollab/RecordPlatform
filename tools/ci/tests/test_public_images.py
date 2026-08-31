from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location(
    "verify_public_images", ROOT / "tools/ci/verify_public_images.py"
)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)
MANIFEST = "sha256:" + "a" * 64
IMAGE_ID = "sha256:" + "b" * 64


class PublicImageTest(unittest.TestCase):
    """Exercise anonymous publication verification without real registry credentials."""

    def setUp(self) -> None:
        """Create all five artifact/tag sets with the four existing stable aliases."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for component in sorted(probe.REQUIRED_COMPONENTS):
            self.add_component(component)

    def add_component(self, component: str) -> None:
        """Add a synthetic packaged component in the same format as metadata-action."""
        package = "verifier-web" if component == "verifier" else component
        repository = f"ghcr.io/soarcollab/recordplatform-{package}"
        (self.directory / f"{component}.tags").write_text(
            "".join(
                f"{repository}:{tag}\n"
                for tag in ("0.0.3", "0.0", "sha-abc1234", "latest")
            ),
            encoding="utf-8",
        )
        (self.directory / f"{component}.tar").write_bytes(b"synthetic archive")

    def docker_success(self, arguments: list[str], config: Path, timeout: int) -> str:
        """Model Docker's documented pull digest and single local image inspection."""
        reference = arguments[-1]
        if arguments[0] == "pull":
            return f"Digest: {MANIFEST}\nStatus: Image is up to date for {reference}\n"
        return json.dumps(
            [
                {
                    "Id": IMAGE_ID,
                    "RepoDigests": [
                        reference.split("@", 1)[0].split(":", 1)[0] + "@" + MANIFEST
                    ],
                }
            ]
        )

    def test_all_aliases_and_new_components_are_pulled(self) -> None:
        """Every generated alias, including a newly added component, is verified."""
        self.add_component("future-service")
        with patch.object(
            probe, "docker_command", side_effect=self.docker_success
        ) as docker:
            probe.verify_images(self.directory, "SoarCollab")
        pulls = [
            call.args[0][-1]
            for call in docker.call_args_list
            if call.args[0][0] == "pull"
        ]
        self.assertEqual(pulls, probe.load_references(self.directory, "SoarCollab"))
        self.assertEqual(len(pulls), 24)
        first_pull = next(
            i
            for i, call in enumerate(docker.call_args_list)
            if call.args[0][0] == "pull"
        )
        self.assertEqual(
            first_pull,
            24,
            "Snapshot every expected identity before mutating local tags",
        )

    def test_missing_empty_invalid_duplicate_or_wrong_package_refs_fail(self) -> None:
        """Malformed tag data cannot silently produce a partially verified release."""
        path = self.directory / "backend.tags"
        original = path.read_text(encoding="utf-8")
        cases = (
            "",
            "\n",
            original + "\n",
            original + original,
            "--help\n",
            original.replace("soarcollab", "elsewhere"),
            original.replace("recordplatform-backend", "recordplatform-fisco"),
        )
        for content in cases:
            with self.subTest(content=content):
                path.write_text(content, encoding="utf-8")
                with self.assertRaises(probe.VerificationError):
                    probe.load_references(self.directory, "soarcollab")
        path.rename(self.directory / "backend.missing")
        with self.assertRaisesRegex(probe.VerificationError, "Missing tag files"):
            probe.load_references(self.directory, "soarcollab")

    def test_missing_or_empty_archive_fails(self) -> None:
        """Require a real paired archive for every tag file."""
        path = self.directory / "backend.tar"
        path.write_bytes(b"")
        with self.assertRaisesRegex(probe.VerificationError, "image archive"):
            probe.load_references(self.directory, "soarcollab")
        path.rename(self.directory / "backend.missing")
        with self.assertRaisesRegex(probe.VerificationError, "image archive"):
            probe.load_references(self.directory, "soarcollab")

    def test_anonymous_environment_excludes_all_inherited_credentials(self) -> None:
        """Docker uses only an explicit blank credential entry and clean environment."""
        secrets = {
            "GH_TOKEN": "do-not-forward",
            "GITHUB_TOKEN": "do-not-forward",
            "DOCKER_AUTH_CONFIG": "do-not-forward",
            "DOCKER_CONFIG": "/cached/login",
            "DOCKER_CONTEXT": "private-context",
            "DOCKER_HOST": "tcp://private-daemon",
            "DOCKER_CUSTOM_HEADERS": "Authorization=do-not-forward",
        }
        with (
            patch.dict(os.environ, secrets),
            patch.object(probe.subprocess, "run") as run,
        ):
            run.return_value.stdout = "result"
            self.assertEqual(
                probe.docker_command(["pull", "reference"], self.directory, 180),
                "result",
            )
        kwargs = run.call_args.kwargs
        self.assertEqual(
            set(kwargs["env"]), {"PATH", "HOME", "DOCKER_CONFIG", "LANG", "LC_ALL"}
        )
        self.assertEqual(kwargs["env"]["HOME"], str(self.directory))
        self.assertEqual(kwargs["env"]["DOCKER_CONFIG"], str(self.directory))
        self.assertEqual(
            run.call_args.args[0][:3], ["docker", "--config", str(self.directory)]
        )
        self.assertTrue(kwargs["check"])
        self.assertEqual(kwargs["timeout"], 180)
        with patch.object(
            probe, "docker_command", side_effect=self.docker_success
        ) as docker:

            def check_config(arguments: list[str], config: Path, timeout: int) -> str:
                """Check a nonempty anonymous auth map prevents helper auto-discovery."""
                self.assertEqual(
                    json.loads((config / "config.json").read_text()),
                    {"auths": {"ghcr.io": {}}},
                )
                return self.docker_success(arguments, config, timeout)

            docker.side_effect = check_config
            probe.verify_images(self.directory, "soarcollab")

    def test_denied_missing_and_digest_mismatch_exhaust_bounded_retries(self) -> None:
        """Denied, missing and mismatched results fail after exactly three attempts."""
        for failure in (
            probe.VerificationError("denied"),
            probe.VerificationError("not found"),
            "Digest: sha256:" + "c" * 64 + "\n",
            "missing digest",
        ):
            pulls: list[str] = []

            def failing(
                arguments: list[str],
                config: Path,
                timeout: int,
                failure: str | Exception = failure,
                pulls: list[str] = pulls,
            ) -> str:
                """Return local metadata but fail the first anonymous alias consistently."""
                if arguments[0] == "pull":
                    pulls.append(arguments[-1])
                    if isinstance(failure, Exception):
                        raise failure
                    return failure
                return self.docker_success(arguments, config, timeout)

            with (
                self.subTest(failure=failure),
                patch.object(probe, "docker_command", side_effect=failing),
                patch.object(probe.time, "sleep") as sleep,
            ):
                with self.assertRaisesRegex(
                    probe.VerificationError, "Public.*irreversible"
                ):
                    probe.verify_images(self.directory, "soarcollab")
                self.assertEqual(len(pulls), 3)
                self.assertEqual(sleep.call_count, 2)

    def test_transient_failure_recovers_and_checks_remaining_aliases(self) -> None:
        """Retry temporary registry failures without skipping later tags."""
        failed = False

        def transient(arguments: list[str], config: Path, timeout: int) -> str:
            """Fail the first pull once, then return successful Docker responses."""
            nonlocal failed
            if arguments[0] == "pull" and not failed:
                failed = True
                raise probe.VerificationError("temporary")
            return self.docker_success(arguments, config, timeout)

        with (
            patch.object(probe, "docker_command", side_effect=transient) as docker,
            patch.object(probe.time, "sleep") as sleep,
        ):
            probe.verify_images(self.directory, "soarcollab")
        self.assertEqual(
            sum(call.args[0][0] == "pull" for call in docker.call_args_list), 21
        )
        sleep.assert_called_once_with(5)

    def test_missing_ambiguous_or_malformed_expected_identity_fails_before_pull(
        self,
    ) -> None:
        """Missing push provenance cannot be reconstructed from the remote pull."""
        for output in (
            "invalid-json",
            "[]",
            "{}",
            '[{"Id": "bad", "RepoDigests": []}]',
            json.dumps([{"Id": IMAGE_ID, "RepoDigests": None}]),
            json.dumps(
                [
                    {
                        "Id": IMAGE_ID,
                        "RepoDigests": [
                            "ghcr.io/soarcollab/recordplatform-backend@" + MANIFEST,
                            "ghcr.io/soarcollab/recordplatform-backend@sha256:"
                            + "c" * 64,
                        ],
                    }
                ]
            ),
        ):
            with (
                self.subTest(output=output),
                patch.object(probe, "docker_command", return_value=output) as docker,
            ):
                with self.assertRaises(probe.VerificationError):
                    probe.verify_images(self.directory, "soarcollab")
                self.assertEqual(docker.call_count, 1)

    def test_changed_pulled_image_id_fails(self) -> None:
        """Even matching reported manifest text cannot hide a changed local image."""
        pulled = False

        def changed(arguments: list[str], config: Path, timeout: int) -> str:
            """Model an image identity change following the first pull."""
            nonlocal pulled
            output = self.docker_success(arguments, config, timeout)
            pulled |= arguments[0] == "pull"
            return output.replace(IMAGE_ID, "sha256:" + "c" * 64) if pulled else output

        with (
            patch.object(probe, "docker_command", side_effect=changed),
            patch.object(probe.time, "sleep"),
            self.assertRaisesRegex(probe.VerificationError, "identity mismatch"),
        ):
            probe.verify_images(self.directory, "soarcollab")

    def test_symlink_tag_file_or_archive_fails_before_docker(self) -> None:
        """Reject artifact symlinks instead of following content outside the bundle."""
        for suffix in ("tags", "tar"):
            with self.subTest(suffix=suffix):
                path = self.directory / f"backend.{suffix}"
                original = self.directory / f"original.{suffix}.saved"
                path.rename(original)
                path.symlink_to(original)
                with (
                    patch.object(probe, "docker_command") as docker,
                    self.assertRaises(probe.VerificationError),
                ):
                    probe.verify_images(self.directory, "soarcollab")
                docker.assert_not_called()
                path.rename(self.directory / f"rejected.{suffix}.symlink")
                original.rename(path)

    def test_cli_routes_both_modes_and_returns_failure(self) -> None:
        """CLI modes dispatch explicitly and verification failures remain nonzero."""
        for flag, operation in (
            ("--images-dir", "verify_images"),
            ("--snapshot", "verify_snapshot"),
        ):
            arguments = [
                "verify_public_images.py",
                flag,
                str(self.directory),
                "--owner",
                "soarcollab",
            ]
            with (
                self.subTest(flag=flag),
                patch.object(probe.sys, "argv", arguments),
                patch.object(probe, operation) as verify,
            ):
                self.assertEqual(probe.main(), 0)
                verify.assert_called_once_with(self.directory, "soarcollab")
                verify.side_effect = probe.VerificationError("synthetic failure")
                with patch("builtins.print") as output:
                    self.assertEqual(probe.main(), 1)
                self.assertIn("synthetic failure", output.call_args.args[0])

    def test_timeout_and_docker_failure_do_not_print_raw_output(self) -> None:
        """Timeouts and command errors remain failures without echoing raw secrets."""
        for error in (
            subprocess.TimeoutExpired("docker", 180),
            subprocess.CalledProcessError(1, "docker", stderr="secret"),
            OSError("secret"),
        ):
            with (
                self.subTest(error=error),
                patch.object(probe.subprocess, "run", side_effect=error),
            ):
                with self.assertRaises(probe.VerificationError) as raised:
                    probe.docker_command(["pull", "reference"], self.directory, 180)
                self.assertNotIn("secret", str(raised.exception))

    def test_snapshot_loads_all_immutable_images_before_checking_aliases(self) -> None:
        """The manual acceptance path never trusts mutable aliases for its baseline."""
        snapshot = self.directory / "snapshot.json"
        snapshot.write_text(
            json.dumps(
                {
                    "tags": ["0.0.3", "0.0", "sha-abc1234", "latest"],
                    "images": dict.fromkeys(probe.REQUIRED_COMPONENTS, MANIFEST),
                }
            ),
            encoding="utf-8",
        )
        with patch.object(
            probe, "docker_command", side_effect=self.docker_success
        ) as docker:
            probe.verify_snapshot(snapshot, "soarcollab")
        pulls = [
            call.args[0][-1]
            for call in docker.call_args_list
            if call.args[0][0] == "pull"
        ]
        self.assertEqual(len(pulls), 25)
        self.assertTrue(
            all(reference.endswith("@" + MANIFEST) for reference in pulls[:5])
        )
        self.assertTrue(all("@" not in reference for reference in pulls[5:]))

    def test_malformed_snapshot_stops_before_network(self) -> None:
        """Missing components, unsafe tags and invalid digests cannot reach Docker."""
        snapshot = self.directory / "snapshot.json"
        cases: tuple[object, ...] = (
            None,
            {},
            {"tags": [], "images": {}},
            {
                "tags": ["--help"],
                "images": dict.fromkeys(probe.REQUIRED_COMPONENTS, MANIFEST),
            },
            {
                "tags": ["latest"],
                "images": dict.fromkeys(probe.REQUIRED_COMPONENTS, "invalid"),
            },
        )
        for data in cases:
            snapshot.write_text(json.dumps(data), encoding="utf-8")
            with (
                self.subTest(data=data),
                patch.object(probe, "docker_command") as docker,
            ):
                with self.assertRaises(probe.VerificationError):
                    probe.verify_snapshot(snapshot, "soarcollab")
                docker.assert_not_called()


class PublicReleaseWorkflowTest(unittest.TestCase):
    """Keep publication checks blocking and outside the credential-free build job."""

    def test_probe_is_bundled_and_runs_after_push_before_release(self) -> None:
        """Require the exact build-bundled helper on every normal release path."""
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        build, publish = workflow.split("\n  publish:", 1)
        self.assertIn("tools/ci/verify_public_images.py", build)
        self.assertNotIn("secrets.GITHUB_TOKEN", build)
        self.assertNotIn("packages: write", build)
        self.assertLess(
            publish.index("name: Push Docker images"),
            publish.index("name: Verify anonymous image pulls"),
        )
        self.assertLess(
            publish.index("name: Verify anonymous image pulls"),
            publish.index("name: Create GitHub Release"),
        )
        verification = publish.split("name: Verify anonymous image pulls", 1)[1].split(
            "- name:", 1
        )[0]
        self.assertIn(
            "python3 release-bundle/tools/ci/verify_public_images.py", verification
        )
        self.assertIn("--images-dir release-bundle/release-images", verification)
        self.assertIn("timeout-minutes: 30", verification)
        for bypass in (
            "continue-on-error",
            "if:",
            "|| true",
            "GITHUB_TOKEN",
            "GH_TOKEN",
        ):
            self.assertNotIn(bypass, verification)

    def test_manual_acceptance_is_read_only_and_not_a_normal_ci_dependency(
        self,
    ) -> None:
        """Keep the historical registry probe explicit and separate from normal PR CI."""
        workflow = (ROOT / ".github/workflows/verify-public-images.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("contents: read", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn(
            "--snapshot tools/ci/fixtures/v0.0.3-public-images.json", workflow
        )
        for forbidden in (
            "pull_request:",
            "push:",
            "schedule:",
            "packages:",
            "secrets.",
            "docker/login",
            "docker push",
            "docker build",
            "action-gh-release",
        ):
            self.assertNotIn(forbidden, workflow)


if __name__ == "__main__":
    unittest.main()
