from __future__ import annotations

import io
import unittest
from contextlib import redirect_stdout
from typing import Mapping

from tools.ci.required_ci_gate import GateInputs, evaluate_gate, main, parse_change_flag


class RequiredCiGateTest(unittest.TestCase):
    """Exercise success, failure, cancellation, skip, and unknown-result policy paths."""

    def setUp(self) -> None:
        """Create an all-applicable successful graph for focused mutations in each test."""

        self.success_results = {
            "changes": "success",
            "backend-test": "success",
            "frontend-test": "success",
            "contract-consistency": "success",
            "security-scan": "success",
            "docs-verification": "success",
            "build-check": "success",
        }

    def gate_inputs(
        self,
        *,
        backend_changed: bool = True,
        contract_changed: bool = True,
        docs_changed: bool = True,
        build_changed: bool = True,
        results: Mapping[str, str] | None = None,
    ) -> GateInputs:
        """Build immutable gate inputs while allowing one policy dimension to change."""

        return GateInputs(
            backend_changed=backend_changed,
            contract_changed=contract_changed,
            docs_changed=docs_changed,
            build_changed=build_changed,
            results=self.success_results if results is None else results,
        )

    def test_accepts_all_required_successes(self) -> None:
        """Accept the fully applicable happy path."""

        self.assertEqual([], evaluate_gate(self.gate_inputs()))

    def test_accepts_only_explicit_skips_for_inapplicable_jobs(self) -> None:
        """Accept skipped conditional jobs while still requiring global jobs to succeed."""

        results = dict(self.success_results)
        for job in (
            "backend-test",
            "contract-consistency",
            "docs-verification",
            "build-check",
        ):
            results[job] = "skipped"

        self.assertEqual(
            [],
            evaluate_gate(
                self.gate_inputs(
                    backend_changed=False,
                    contract_changed=False,
                    docs_changed=False,
                    build_changed=False,
                    results=results,
                )
            ),
        )

    def test_rejects_failure_and_cancelled_results(self) -> None:
        """Reject terminal failures and cancellations for required jobs."""

        results = dict(self.success_results)
        results["security-scan"] = "failure"
        results["frontend-test"] = "cancelled"

        violations = evaluate_gate(self.gate_inputs(results=results))

        self.assertTrue(any("security-scan" in violation for violation in violations))
        self.assertTrue(any("frontend-test" in violation for violation in violations))

    def test_rejects_unexpected_skip_for_required_job(self) -> None:
        """Reject a conditional-graph error that skips an applicable contract check."""

        results = dict(self.success_results)
        results["contract-consistency"] = "skipped"

        self.assertEqual(
            [
                "contract-consistency: result='skipped', expected='success' "
                "for required job"
            ],
            evaluate_gate(self.gate_inputs(results=results)),
        )

    def test_rejects_unexpected_execution_for_inapplicable_job(self) -> None:
        """Reject divergence between changed-path policy and the actual job graph."""

        violations = evaluate_gate(self.gate_inputs(docs_changed=False))

        self.assertEqual(
            [
                "docs-verification: result='success', expected='skipped' "
                "for inapplicable job"
            ],
            violations,
        )

    def test_rejects_empty_and_unknown_results(self) -> None:
        """Reject missing or future GitHub result values until policy explicitly handles them."""

        results = dict(self.success_results)
        results["changes"] = ""
        results["build-check"] = "neutral"

        violations = evaluate_gate(self.gate_inputs(results=results))

        self.assertIn("changes: unknown result ''", violations)
        self.assertIn("build-check: unknown result 'neutral'", violations)

    def test_parses_only_exact_github_boolean_outputs(self) -> None:
        """Reject empty, mixed-case, or otherwise ambiguous path-filter outputs."""

        self.assertTrue(parse_change_flag("backend", "true"))
        self.assertFalse(parse_change_flag("backend", "false"))
        for invalid in ("", "True", "1", "yes"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    parse_change_flag("backend", invalid)

    def test_command_returns_non_zero_for_invalid_change_output(self) -> None:
        """Exercise the workflow-facing CLI fail-closed path for an empty output."""

        args = [
            "--backend-changed",
            "",
            "--contract-changed",
            "false",
            "--docs-changed",
            "false",
            "--build-changed",
            "false",
        ]
        for job in self.success_results:
            args.extend([f"--{job}-result", self.success_results[job]])

        with redirect_stdout(io.StringIO()):
            self.assertEqual(1, main(args))


if __name__ == "__main__":
    unittest.main()
