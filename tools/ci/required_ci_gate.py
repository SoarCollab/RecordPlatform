#!/usr/bin/env python3
"""Fail closed when a required or intentionally skipped CI job has an unsafe result."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from typing import Mapping, Sequence


KNOWN_RESULTS = frozenset({"success", "failure", "cancelled", "skipped"})


@dataclass(frozen=True)
class GateInputs:
    """Capture changed-path decisions and the terminal result of every governed job."""

    backend_changed: bool
    contract_changed: bool
    docs_changed: bool
    build_changed: bool
    results: Mapping[str, str]


def parse_change_flag(name: str, value: str) -> bool:
    """Parse a GitHub Actions boolean output without silently accepting an empty value."""

    if value == "true":
        return True
    if value == "false":
        return False
    raise ValueError(f"{name} must be 'true' or 'false'; got {value!r}")


def evaluate_gate(inputs: GateInputs) -> list[str]:
    """Return every CI graph violation so one run exposes the complete unsafe state."""

    expected_to_run = {
        "changes": True,
        "backend-test": inputs.backend_changed,
        "frontend-test": True,
        "contract-consistency": inputs.contract_changed,
        "security-scan": True,
        "docs-verification": inputs.docs_changed,
        "build-check": inputs.build_changed,
    }
    violations: list[str] = []

    for job, required in expected_to_run.items():
        result = inputs.results.get(job, "")
        if result not in KNOWN_RESULTS:
            violations.append(f"{job}: unknown result {result!r}")
            continue

        expected_result = "success" if required else "skipped"
        if result != expected_result:
            applicability = "required" if required else "inapplicable"
            violations.append(
                f"{job}: result={result!r}, expected={expected_result!r} "
                f"for {applicability} job"
            )

    return violations


def build_parser() -> argparse.ArgumentParser:
    """Build the command-line contract used by the Required CI workflow job."""

    parser = argparse.ArgumentParser(description=__doc__)
    for change_name in ("backend", "contract", "docs", "build"):
        parser.add_argument(f"--{change_name}-changed", required=True)
    for job_name in (
        "changes",
        "backend-test",
        "frontend-test",
        "contract-consistency",
        "security-scan",
        "docs-verification",
        "build-check",
    ):
        parser.add_argument(f"--{job_name}-result", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Evaluate workflow results and return a non-zero status for any unsafe state."""

    args = build_parser().parse_args(argv)
    try:
        inputs = GateInputs(
            backend_changed=parse_change_flag("backend", args.backend_changed),
            contract_changed=parse_change_flag("contract", args.contract_changed),
            docs_changed=parse_change_flag("docs", args.docs_changed),
            build_changed=parse_change_flag("build", args.build_changed),
            results={
                "changes": args.changes_result,
                "backend-test": args.backend_test_result,
                "frontend-test": args.frontend_test_result,
                "contract-consistency": args.contract_consistency_result,
                "security-scan": args.security_scan_result,
                "docs-verification": args.docs_verification_result,
                "build-check": args.build_check_result,
            },
        )
    except ValueError as exc:
        print(f"Required CI rejected change detection output: {exc}")
        return 1

    violations = evaluate_gate(inputs)
    if violations:
        print("Required CI rejected the workflow graph:")
        for violation in violations:
            print(f"- {violation}")
        return 1

    print("Required CI accepted all required successes and explicit inapplicable skips.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
