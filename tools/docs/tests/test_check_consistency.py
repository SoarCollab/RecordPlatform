"""Regression tests for documentation evidence consistency checks."""

from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.docs import check_consistency


REPO_ROOT = Path(__file__).resolve().parents[3]


class DocumentationEvidenceConsistencyTest(unittest.TestCase):
    """Verify positive repository evidence and fail-closed drift detection."""

    def setUp(self) -> None:
        """Create an isolated temporary repository root for mutation cases."""
        self.temp_directory = tempfile.TemporaryDirectory()
        self.temp_root = Path(self.temp_directory.name)

    def tearDown(self) -> None:
        """Remove the isolated temporary repository root."""
        self.temp_directory.cleanup()

    def copy_file(self, relative_path: str) -> Path:
        """Copy a repository file into the temporary root and return its path."""
        source = REPO_ROOT / relative_path
        destination = self.temp_root / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        return destination

    def test_repository_evidence_contract_passes(self) -> None:
        """Require the checked-in documentation and evidence to remain self-consistent."""
        result = check_consistency.check_evidence(REPO_ROOT)
        self.assertEqual([], result.issues)

    def test_tampered_load_smoke_summary_is_rejected(self) -> None:
        """Reject a derived metric that no longer matches the retained artifact."""
        self.copy_file(str(check_consistency.DIRECT_UPLOAD_EVIDENCE_PATH))
        for relative_path in check_consistency.DIRECT_UPLOAD_EVIDENCE_DOCS:
            destination = self.copy_file(relative_path)
            if relative_path == "ROADMAP.md":
                content = destination.read_text(encoding="utf-8")
                destination.write_text(content.replace("wall 414 ms", "wall 415 ms", 1), encoding="utf-8")

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_direct_upload_evidence(self.temp_root, result)

        self.assertTrue(any("414 ms" in issue for issue in result.issues), result.issues)

    def test_tampered_retained_artifact_is_rejected(self) -> None:
        """Reject a retained report whose bytes or canonical values were changed."""
        artifact_path = self.copy_file(str(check_consistency.DIRECT_UPLOAD_EVIDENCE_PATH))
        for relative_path in check_consistency.DIRECT_UPLOAD_EVIDENCE_DOCS:
            self.copy_file(relative_path)
        content = artifact_path.read_text(encoding="utf-8")
        artifact_path.write_text(content.replace('"wallMillis" : 414', '"wallMillis" : 415'), encoding="utf-8")

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_direct_upload_evidence(self.temp_root, result)

        self.assertTrue(any("digest mismatch" in issue for issue in result.issues), result.issues)
        self.assertTrue(any("wallMillis" in issue for issue in result.issues), result.issues)

    def test_missing_grant_consume_route_is_rejected(self) -> None:
        """Reject a root API guide that drops a required grant consume endpoint."""
        api_path = self.copy_file("API_DOCUMENTATION.md")
        content = api_path.read_text(encoding="utf-8")
        api_path.write_text(
            content.replace("POST /api/v1/public/key-grants/consume", "POST /api/v1/public/key-grants/removed"),
            encoding="utf-8",
        )

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_api_security_contract(self.temp_root, result)

        self.assertTrue(any("public/key-grants/consume" in issue for issue in result.issues), result.issues)

    def test_missing_p3_run_is_rejected(self) -> None:
        """Reject a bilingual delivery matrix that drops an exact P3 workflow run."""
        for relative_path in check_consistency.P3_EVIDENCE_DOCS:
            destination = self.copy_file(relative_path)
            if relative_path.startswith("docs/en/"):
                content = destination.read_text(encoding="utf-8")
                destination.write_text(content.replace("30245935210", "missing-run"), encoding="utf-8")

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_delivery_evidence_contract(self.temp_root, result)

        self.assertTrue(any("30245935210" in issue for issue in result.issues), result.issues)

    def test_test_snapshot_count_drift_is_rejected(self) -> None:
        """Reject TESTING.md counts that differ from the exact canonical source tree."""
        test_files = (
            "platform-backend/backend-common/src/test/java/CommonTest.java",
            "platform-storage/src/test/java/StorageIT.java",
            "platform-frontend/src/example.test.ts",
            "platform-verifier/sdk/src/test/java/VerifierTest.java",
            "platform-fisco/src/test/java/FiscoTest.java",
            "platform-api/src/test/java/ApiTest.java",
            "tools/docs/tests/test_checker.py",
        )
        for relative_path in test_files:
            file_path = self.temp_root / relative_path
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.touch()

        testing_path = self.temp_root / "TESTING.md"
        testing_path.write_text(
            "\n".join(
                (
                    "| Component | Test files |",
                    "| --- | ---: |",
                    "| `platform-backend/backend-common` | 1 |",
                    "| `platform-backend/backend-api` | 0 |",
                    "| `platform-backend/backend-service` | 0 |",
                    "| `platform-backend/backend-web` | 0 |",
                    "| `platform-backend` | 1 |",
                    "| `platform-storage` | 1 |",
                    "| `platform-frontend` | 1 |",
                    "| `platform-verifier` | 1 |",
                    "| `platform-fisco` | 1 |",
                    "| `platform-api` | 1 |",
                    "| `tools/ci` | 0 |",
                    "| `tools/contracts` | 0 |",
                    "| `tools/docs` | 1 |",
                    "| `tools` | 1 |",
                    "| `total` | 8 |",
                )
            ),
            encoding="utf-8",
        )

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_test_snapshot(self.temp_root, result)

        self.assertTrue(any("total" in issue and "actual=7" in issue for issue in result.issues), result.issues)

    def test_test_snapshot_heading_drift_is_rejected(self) -> None:
        """Reject a snapshot heading whose total differs from the checked table and source tree."""
        testing_path = self.copy_file("TESTING.md")
        content = testing_path.read_text(encoding="utf-8")
        testing_path.write_text(content.replace("（314 files）", "（313 files）", 1), encoding="utf-8")

        result = check_consistency.CheckResult("evidence")
        with patch.object(
            check_consistency,
            "collect_test_file_counts",
            return_value=check_consistency.collect_test_file_counts(REPO_ROOT),
        ):
            check_consistency.check_test_snapshot(self.temp_root, result)

        self.assertTrue(any("heading mismatch" in issue for issue in result.issues), result.issues)

    def test_readme_env_command_drift_is_rejected(self) -> None:
        """Reject bilingual quick-start instructions that lose an executable env step."""
        self.copy_file("README.md")
        chinese_readme = self.copy_file("README_CN.md")
        content = chinese_readme.read_text(encoding="utf-8")
        chinese_readme.write_text(content.replace("source .env", "# source removed", 1), encoding="utf-8")

        result = check_consistency.CheckResult("evidence")
        check_consistency.check_readme_env_parity(self.temp_root, result)

        self.assertTrue(any("source .env" in issue for issue in result.issues), result.issues)


if __name__ == "__main__":
    unittest.main()
