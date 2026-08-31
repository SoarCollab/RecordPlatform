"""Regression tests for documentation evidence consistency checks."""

from __future__ import annotations

import re
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

    def test_prometheus_machine_identity_docs_keep_password_file_and_verified_tls(self) -> None:
        """Require bilingual least-privilege setup without anonymous or insecure scrape examples."""
        for language in ("en", "zh"):
            content = (REPO_ROOT / "docs" / language / "deployment/monitoring.md").read_text(encoding="utf-8")
            for token in ("PROMETHEUS_SCRAPE_ENABLED", "PROMETHEUS_SCRAPE_USERNAME",
                          "PROMETHEUS_SCRAPE_PASSWORD_HASH", "security.prometheus-scrape.password-hash",
                          "PROMETHEUS_SCRAPE", "password_file:", "scheme: https", "ca_file:",
                          "--cacert", "--user collector", "promtool check config", "SIGHUP", "401", "400"):
                with self.subTest(language=language, token=token):
                    self.assertIn(token, content)
            self.assertNotIn("insecure_skip_verify", content)
            self.assertNotRegex(content, r"curl[^\n]*\s-k\b")
            self.assertNotIn("targets: ['backend:8000']", content)
        environment = (REPO_ROOT / ".env.example").read_text(encoding="utf-8")
        self.assertIn("PROMETHEUS_SCRAPE_ENABLED=false", environment)
        self.assertIn("PROMETHEUS_SCRAPE_PASSWORD_HASH=''", environment)

    def test_flyway_bootstrap_permissions_remain_narrow_and_version_scoped(self) -> None:
        """Keep bootstrap permission guidance separate from historical repair commands."""
        content = (REPO_ROOT / "docs/operations/flyway-release-compatibility.md").read_text(encoding="utf-8")
        section = content.split("## MySQL bootstrap permissions\n", 1)[1].split("\n## 1. Scope", 1)[0]
        required = (
            "Flyway 11.7.2", "Druid 1.2.28", "MySQL 8.4",
            "SELECT variable_name FROM performance_schema.user_variables_by_thread WHERE variable_value IS NOT NULL;",
            "SELECT @@foreign_key_checks;", "connection disabled",
            "not a universal grant requirement", "session user-variable names and values",
            "separate migration/runtime credentials", "CURRENT_USER()", "CURRENT_ROLE()",
            "SHOW GRANTS;", "no failed rows", "next restart even when no migrations are pending",
            "do not edit history or run automatic repair",
            "https://github.com/flyway/flyway/issues/3202",
            "https://github.com/alibaba/druid/issues/3626",
            "https://dev.mysql.com/doc/refman/8.4/en/performance-schema-user-variable-tables.html",
        )
        for token in required:
            with self.subTest(token=token):
                self.assertIn(token, section)

        sql_blocks = "\n".join(re.findall(r"```sql\n(.*?)\n```", section, re.DOTALL))
        grants = re.findall(r"\bGRANT\s+[^;]+;", sql_blocks, re.IGNORECASE)
        revokes = re.findall(r"\bREVOKE\s+[^;]+;", sql_blocks, re.IGNORECASE)
        self.assertEqual(
            ["GRANT SELECT ON performance_schema.user_variables_by_thread TO '<migration-user>'@'<migration-host>';"],
            grants,
        )
        self.assertEqual(
            ["REVOKE SELECT ON performance_schema.user_variables_by_thread FROM '<migration-user>'@'<migration-host>';"],
            revokes,
        )
        self.assertIn("SHOW GRANTS FOR '<migration-user>'@'<migration-host>';", sql_blocks)
        self.assertNotRegex(sql_blocks, r"(?i)\b(?:UPDATE|DELETE|INSERT|REPAIR|DROP|ALTER)\b")
        self.assertNotIn("flyway:repair", section)

    def test_bilingual_bootstrap_and_troubleshooting_link_canonical_permissions(self) -> None:
        """Keep all deployment entrypoints connected to the same permission procedure."""
        canonical_link = "../../operations/flyway-release-compatibility.md#mysql-bootstrap-permissions"
        for language in ("en", "zh"):
            for relative_path in ("deployment/environment-setup.md", "troubleshooting/common-issues.md"):
                path = REPO_ROOT / "docs" / language / relative_path
                with self.subTest(path=path):
                    content = path.read_text(encoding="utf-8")
                    self.assertIn(canonical_link, content)
                    for token in ("11.7.2", "1.2.28", "MySQL 8.4", "performance_schema.user_variables_by_thread"):
                        self.assertIn(token, content)
                    self.assertTrue((path.parent / canonical_link.split("#", 1)[0]).resolve().is_file())

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
        total = check_consistency.collect_test_file_counts(REPO_ROOT)["total"]
        testing_path.write_text(
            content.replace(f"（{total} files）", f"（{total - 1} files）", 1),
            encoding="utf-8",
        )

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
