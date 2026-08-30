from __future__ import annotations

import re
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
TEST_WORKFLOW = PROJECT_ROOT / ".github/workflows/test.yml"
DOCS_WORKFLOW = PROJECT_ROOT / ".github/workflows/docs-consistency.yml"
CODECOV_CONFIG = PROJECT_ROOT / ".codecov.yml"


def job_block(workflow: str, job_name: str) -> str:
    """Extract one top-level workflow job without adding a YAML parser dependency to CI."""

    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\n(?P<body>.*?)(?=^  [a-z0-9-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"workflow job not found: {job_name}")
    return match.group(0)


class RequiredCiWorkflowTest(unittest.TestCase):
    """Lock the workflow wiring that makes the Required CI result fail closed."""

    @classmethod
    def setUpClass(cls) -> None:
        """Read governance files once so each assertion examines the same revision."""

        cls.test_workflow = TEST_WORKFLOW.read_text(encoding="utf-8")
        cls.docs_workflow = DOCS_WORKFLOW.read_text(encoding="utf-8")
        cls.codecov_config = CODECOV_CONFIG.read_text(encoding="utf-8")

    def test_global_frontend_coverage_and_security_jobs_cannot_path_skip(self) -> None:
        """Require real coverage upload and vulnerability scan results on every PR."""

        frontend = job_block(self.test_workflow, "frontend-test")
        security = job_block(self.test_workflow, "security-scan")

        self.assertNotRegex(frontend, r"(?m)^    if:")
        self.assertIn("fail_ci_if_error: true", frontend)
        self.assertIn("disable_search: true", frontend)
        self.assertNotRegex(security, r"(?m)^    if:")
        self.assertIn("exit-code: '1'", security)

    def test_backend_coverage_files_use_codecov_supported_delimiters(self) -> None:
        """Prevent newline-delimited paths from becoming one nonexistent Codecov filename."""

        backend = job_block(self.test_workflow, "backend-test")
        expected_files = (
            "files: platform-backend/backend-common/target/site/jacoco/jacoco.xml,"
            "platform-backend/backend-service/target/site/jacoco/jacoco.xml,"
            "platform-backend/backend-web/target/site/jacoco/jacoco.xml"
        )

        self.assertIn(expected_files, backend)
        self.assertNotIn("files: |", backend)

    def test_contract_job_exports_openapi_from_the_checked_out_revision(self) -> None:
        """Prevent frontend-only contract changes from consuming a stale backend artifact."""

        contract = job_block(self.test_workflow, "contract-consistency")

        self.assertIn("needs: [changes]", contract)
        self.assertIn("needs.changes.outputs.contract == 'true'", contract)
        self.assertIn("OpenApiContractExportTest", contract)
        self.assertIn(
            "../platform-backend/backend-web/target/openapi/openapi.json",
            contract,
        )
        self.assertNotIn("actions/download-artifact", contract)

    def test_docs_gate_is_reusable_audited_and_conditionally_governed(self) -> None:
        """Require docs changes, including Dependabot lockfiles, to use the full docs gate."""

        docs = job_block(self.test_workflow, "docs-verification")

        self.assertIn("needs.changes.outputs.docs == 'true'", docs)
        self.assertIn("uses: ./.github/workflows/docs-consistency.yml", docs)
        self.assertIn("workflow_call:", self.docs_workflow)
        self.assertIn("install --frozen-lockfile", self.docs_workflow)
        self.assertIn("audit --audit-level high", self.docs_workflow)
        self.assertIn("docs:build", self.docs_workflow)

    def test_required_ci_aggregates_every_governed_job(self) -> None:
        """Keep the stable required context connected to all conditional and global jobs."""

        required_ci = job_block(self.test_workflow, "required-ci")
        for dependency in (
            "changes",
            "backend-test",
            "frontend-test",
            "contract-consistency",
            "security-scan",
            "docs-verification",
            "build-check",
        ):
            self.assertIn(f"      - {dependency}", required_ci)
            self.assertIn(f"needs.{dependency}.result", required_ci)
        self.assertIn("if: always()", required_ci)
        self.assertIn("tools/ci/required_ci_gate.py", required_ci)

    def test_build_waits_for_security_and_uses_explicit_path_policy(self) -> None:
        """Prevent Build Verification from reporting success independently of security."""

        build = job_block(self.test_workflow, "build-check")

        self.assertIn("security-scan", build)
        self.assertIn("needs.changes.outputs.build == 'true'", build)
        self.assertIn("!failure()", build)
        self.assertIn("!cancelled()", build)

    def test_patch_coverage_policy_is_strict_and_fail_closed(self) -> None:
        """Lock the required patch threshold and missing-report behavior."""

        self.assertIn("target: 80%", self.codecov_config)
        self.assertIn("threshold: 0%", self.codecov_config)
        self.assertIn("informational: false", self.codecov_config)
        self.assertIn("if_not_found: failure", self.codecov_config)
        self.assertIn("if_ci_failed: error", self.codecov_config)


class MonitoringImageTest(unittest.TestCase):
    """Prevent the nonexistent Jaeger minor-only tag and documentation image drift."""

    def test_compose_and_docs_use_verified_jaeger_image(self) -> None:
        """Require exactly one identical, immutable Jaeger image in each deployment asset."""
        expected_image = (
            "jaegertracing/all-in-one:1.68.0@sha256:"
            "6279882637ae03e70f519965d2ba5ca84cb785f4baf4f0d7237e827a37c33a42"
        )
        for relative_path in (
            "docker-compose.infra.yml",
            "docs/en/deployment/docker-compose.md",
            "docs/zh/deployment/docker-compose.md",
        ):
            with self.subTest(path=relative_path):
                content = (PROJECT_ROOT / relative_path).read_text(encoding="utf-8")
                images = re.findall(
                    r"(?m)^\s+image:\s*(jaegertracing/all-in-one:\S+)", content
                )
                self.assertEqual(images, [expected_image])


if __name__ == "__main__":
    unittest.main()
