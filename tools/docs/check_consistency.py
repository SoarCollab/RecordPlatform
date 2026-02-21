#!/usr/bin/env python3
"""Validate documentation consistency against backend/OpenAPI/runtime facts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable


CORE_WORKFLOWS = ("test.yml", "perf-smoke.yml", "docs.yml", "security-poc.yml")

ROUTE_DOC_TARGETS = (
    "README.md",
    "README_CN.md",
    "API_DOCUMENTATION.md",
    "TESTING.md",
    "ROADMAP.md",
    "scripts/README.md",
    "tools/k6/README.md",
    "docs/en",
    "docs/zh",
)

ENV_DOC_FILES = (
    "docs/en/getting-started/configuration.md",
    "docs/zh/getting-started/configuration.md",
    "docs/en/deployment/production.md",
    "docs/zh/deployment/production.md",
    "scripts/README.md",
)

LEGACY_ROUTE_RULES = (
    re.compile(r"/api/v1/auth/sse-token\b"),
    re.compile(r"/api/v1/files/upload/start\b"),
    re.compile(r"/api/v1/files/upload/chunk\b"),
    re.compile(r"/api/v1/files/upload/complete\b"),
    re.compile(r"/api/v1/files/upload/progress(?:\?|\b)"),
    re.compile(r"/api/v1/files/address\b"),
    re.compile(r"/api/v1/files/decryptInfo\b"),
    re.compile(r"POST\s+/api/v1/files/share(?!/)"),
    re.compile(r"/files/upload/start\b"),
    re.compile(r"/files/upload/chunk\b"),
    re.compile(r"/files/upload/complete\b"),
    re.compile(r"/files/upload/progress\b"),
)

ALLOWED_NON_OPENAPI_METHOD_ROUTES = {
    ("POST", "/api/v1/auth/login"),
    ("POST", "/api/v1/auth/logout"),
    ("GET", "/api/v1/share/**"),
}

ALLOWED_NON_OPENAPI_PATHS = {
    "/api/v1/share/**",
}

FORBIDDEN_ENV_VARS = {
    "SERVER_SSL_KEY_STORE",
    "SERVER_SSL_KEY_STORE_PASSWORD",
    "SECURITY_REQUIRE_SSL",
    "SECURITY_HTTP_REDIRECT_PORT",
    "NACOS_SERVER_ADDR",
    "SW_COLLECTOR",
}

DEPRECATED_ENV_ALLOWLIST: set[str] = set()
DOC_ONLY_ENV_ALLOWLIST = {"JAVA_OPTS"}

REQUIRED_DOC_VARS = {
    "SSL_KEY_STORE",
    "SSL_KEY_STORE_PASSWORD",
    "REQUIRE_SSL",
    "HTTP_REDIRECT_PORT",
    "DUBBO_FISCO_PORT",
    "DUBBO_STORAGE_PORT",
    "NACOS_HOST",
    "NACOS_PORT",
    "SW_AGENT_COLLECTOR_BACKEND_SERVICES",
}

METHOD_ROUTE_PATTERN = re.compile(
    r"\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+"
    r"(/api/v1/[A-Za-z0-9_{}./*-]+(?:\?[A-Za-z0-9_=&%.-]+)?)"
)
ENV_VAR_PATTERN = re.compile(r"\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b")
ENV_PLACEHOLDER_PATTERN = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(?::[^}]*)?\}")
ENV_ASSIGN_PATTERN = re.compile(r"^\s*([A-Z][A-Z0-9_]*)=", re.MULTILINE)
ENV_EXPORT_PATTERN = re.compile(r"\bexport\s+([A-Z][A-Z0-9_]*)=")
MIGRATION_VERSION_PATTERN = re.compile(r"^V([0-9]+\.[0-9]+\.[0-9]+)__")


@dataclass
class CheckResult:
    """Store check result details for one validation category."""

    name: str
    issues: list[str] = field(default_factory=list)

    def ok(self) -> bool:
        """Return whether the check passed without issues."""
        return not self.issues



def get_repo_root() -> Path:
    """Resolve repository root based on the script location."""
    return Path(__file__).resolve().parents[2]



def parse_args() -> argparse.Namespace:
    """Parse CLI arguments for selective consistency checks."""
    parser = argparse.ArgumentParser(description="Check documentation consistency.")
    parser.add_argument("--check-routes", action="store_true", help="Validate API routes against OpenAPI")
    parser.add_argument("--check-env", action="store_true", help="Validate documented env vars against code/env sources")
    parser.add_argument("--check-roadmap", action="store_true", help="Validate ROADMAP baseline snapshot")
    parser.add_argument(
        "--openapi",
        type=Path,
        default=Path("platform-backend/backend-web/target/openapi/openapi.json"),
        help="Path to exported openapi.json (relative to repo root by default)",
    )
    return parser.parse_args()



def resolve_targets(root: Path, targets: Iterable[str]) -> list[Path]:
    """Expand file/directory targets into a deterministic markdown file list."""
    files: list[Path] = []
    for target in targets:
        path = root / target
        if path.is_file():
            files.append(path)
        elif path.is_dir():
            files.extend(sorted(path.rglob("*.md")))
    return sorted(set(files))



def normalize_route(route: str) -> str:
    """Normalize route literals by dropping query strings and trailing punctuation."""
    normalized = route.split("?", 1)[0]
    return normalized.rstrip(".,)`\"")



def line_number_from_index(content: str, index: int) -> int:
    """Compute one-based line number from a character index."""
    return content.count("\n", 0, index) + 1



def load_openapi_methods(openapi_path: Path) -> dict[str, set[str]]:
    """Load OpenAPI paths and supported HTTP methods from json artifact."""
    with openapi_path.open("r", encoding="utf-8") as file:
        payload = json.load(file)
    return {
        path: {method.upper() for method in operations.keys()}
        for path, operations in payload.get("paths", {}).items()
    }



def check_routes(root: Path, openapi_path: Path) -> CheckResult:
    """Validate method+route mentions in docs against OpenAPI and legacy deny list."""
    result = CheckResult("routes")
    if not openapi_path.exists():
        result.issues.append(f"Missing OpenAPI artifact: {openapi_path}")
        return result

    openapi_methods = load_openapi_methods(openapi_path)
    seen_issues: set[str] = set()

    for file_path in resolve_targets(root, ROUTE_DOC_TARGETS):
        content = file_path.read_text(encoding="utf-8")
        rel_path = file_path.relative_to(root)

        for legacy_pattern in LEGACY_ROUTE_RULES:
            for match in legacy_pattern.finditer(content):
                line = line_number_from_index(content, match.start())
                issue = f"{rel_path}:{line} contains legacy route pattern: {match.group(0)}"
                if issue not in seen_issues:
                    seen_issues.add(issue)
                    result.issues.append(issue)

        for match in METHOD_ROUTE_PATTERN.finditer(content):
            method = match.group(1).upper()
            route = normalize_route(match.group(2))
            line = line_number_from_index(content, match.start())

            if (method, route) in ALLOWED_NON_OPENAPI_METHOD_ROUTES:
                continue
            if route in ALLOWED_NON_OPENAPI_PATHS:
                continue

            supported_methods = openapi_methods.get(route)
            if supported_methods is None:
                issue = f"{rel_path}:{line} route not found in OpenAPI: {method} {route}"
            elif method not in supported_methods:
                expected = ",".join(sorted(supported_methods))
                issue = f"{rel_path}:{line} method mismatch: {method} {route} (OpenAPI: {expected})"
            else:
                continue

            if issue not in seen_issues:
                seen_issues.add(issue)
                result.issues.append(issue)

    return result



def collect_known_env_vars(root: Path) -> set[str]:
    """Collect environment variable names from code placeholders and env sources."""
    known_vars: set[str] = set()

    app_files = [path for path in root.rglob("application*.yml") if "target" not in path.parts]
    env_files = [
        root / ".env.example",
        root / "platform-frontend/.env.example",
        root / "scripts/env.sh",
    ]

    for file_path in app_files + env_files:
        if not file_path.exists():
            continue
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        known_vars.update(ENV_PLACEHOLDER_PATTERN.findall(content))
        known_vars.update(ENV_ASSIGN_PATTERN.findall(content))
        known_vars.update(ENV_EXPORT_PATTERN.findall(content))

    return known_vars



def collect_documented_env_vars(root: Path) -> tuple[dict[str, list[str]], list[str]]:
    """Extract documented env vars and forbidden var usages from target docs."""
    documented: dict[str, list[str]] = {}
    forbidden_usages: list[str] = []

    for rel_file in ENV_DOC_FILES:
        file_path = root / rel_file
        if not file_path.exists():
            continue
        content = file_path.read_text(encoding="utf-8")

        for env_name in ENV_VAR_PATTERN.findall(content):
            documented.setdefault(env_name, []).append(rel_file)

        for forbidden in FORBIDDEN_ENV_VARS:
            for match in re.finditer(rf"\b{re.escape(forbidden)}\b", content):
                line = line_number_from_index(content, match.start())
                forbidden_usages.append(f"{rel_file}:{line} contains deprecated variable {forbidden}")

    return documented, sorted(set(forbidden_usages))



def check_env_vars(root: Path) -> CheckResult:
    """Validate env-variable documentation against code and expected naming rules."""
    result = CheckResult("env")
    known_vars = collect_known_env_vars(root)
    documented_vars, forbidden_usages = collect_documented_env_vars(root)

    result.issues.extend(forbidden_usages)

    for env_name, sources in sorted(documented_vars.items()):
        if env_name in known_vars:
            continue
        if env_name in DEPRECATED_ENV_ALLOWLIST:
            continue
        if env_name in DOC_ONLY_ENV_ALLOWLIST:
            continue
        files = ", ".join(sorted(set(sources)))
        result.issues.append(f"Unknown documented env variable: {env_name} (in {files})")

    missing_required = sorted(var for var in REQUIRED_DOC_VARS if var not in documented_vars)
    for missing in missing_required:
        result.issues.append(f"Required env variable not documented: {missing}")

    return result



def count_rest_controllers(root: Path) -> int:
    """Count @RestController occurrences as roadmap baseline metric."""
    java_root = root / "platform-backend/backend-web/src/main/java"
    total = 0
    for file_path in java_root.rglob("*.java"):
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        total += len(re.findall(r"@RestController", content))
    return total



def count_backend_service_classes(root: Path) -> int:
    """Count backend service java classes in backend-service module."""
    service_root = root / "platform-backend/backend-service/src/main/java"
    return sum(1 for _ in service_root.rglob("*.java"))



def count_backend_test_files(root: Path) -> int:
    """Count backend test files using *Test.java and *IT.java naming rules."""
    backend_root = root / "platform-backend"
    total = 0
    for file_path in backend_root.rglob("*.java"):
        name = file_path.name
        if name.endswith("Test.java") or name.endswith("IT.java"):
            total += 1
    return total



def parse_semver(version: str) -> tuple[int, int, int]:
    """Parse semantic version string into numeric tuple for ordering."""
    major, minor, patch = version.split(".")
    return int(major), int(minor), int(patch)



def collect_migration_versions(root: Path) -> list[str]:
    """Collect migration versions from Flyway SQL filenames."""
    migration_root = root / "platform-backend/backend-web/src/main/resources/db/migration"
    versions: list[str] = []
    for file_path in migration_root.iterdir():
        match = MIGRATION_VERSION_PATTERN.match(file_path.name)
        if match:
            versions.append(match.group(1))
    return sorted(versions, key=parse_semver)



def count_core_workflows(root: Path) -> tuple[int, list[str]]:
    """Count required core workflows and report any missing entries."""
    workflow_root = root / ".github/workflows"
    present = [name for name in CORE_WORKFLOWS if (workflow_root / name).exists()]
    missing = [name for name in CORE_WORKFLOWS if (workflow_root / name).exists() is False]
    return len(present), missing



def extract_roadmap_value(roadmap_content: str, label: str) -> str | None:
    """Extract baseline table value cell for a given roadmap metric label."""
    pattern = re.compile(rf"^\|\s*{re.escape(label)}\s*\|\s*([^|]+?)\s*\|", re.MULTILINE)
    match = pattern.search(roadmap_content)
    return match.group(1).strip() if match else None



def parse_leading_int(value: str) -> int | None:
    """Parse first integer token from a roadmap value cell."""
    match = re.search(r"\d+", value)
    return int(match.group(0)) if match else None



def check_roadmap(root: Path) -> CheckResult:
    """Validate roadmap baseline snapshot values against filesystem-derived metrics."""
    result = CheckResult("roadmap")
    roadmap_path = root / "ROADMAP.md"
    if not roadmap_path.exists():
        result.issues.append(f"Missing ROADMAP file: {roadmap_path}")
        return result

    roadmap_content = roadmap_path.read_text(encoding="utf-8")

    expected_rest = count_rest_controllers(root)
    expected_services = count_backend_service_classes(root)
    expected_tests = count_backend_test_files(root)

    migration_versions = collect_migration_versions(root)
    expected_migration_count = len(migration_versions)
    expected_migration_range = f"V{migration_versions[0]} ~ V{migration_versions[-1]}" if migration_versions else ""

    expected_workflows, missing_workflows = count_core_workflows(root)
    if missing_workflows:
        result.issues.append(f"Missing core workflows: {', '.join(missing_workflows)}")

    snapshot_checks = [
        ("REST 控制器", expected_rest),
        ("后端服务类", expected_services),
        ("后端测试文件", expected_tests),
        ("数据库迁移", expected_migration_count),
        ("CI 流水线（核心）", expected_workflows),
    ]

    for label, expected in snapshot_checks:
        value = extract_roadmap_value(roadmap_content, label)
        if value is None:
            result.issues.append(f"ROADMAP baseline row not found: {label}")
            continue
        actual = parse_leading_int(value)
        if actual is None:
            result.issues.append(f"ROADMAP value has no numeric count for {label}: {value}")
            continue
        if actual != expected:
            result.issues.append(f"ROADMAP mismatch for {label}: documented={actual}, actual={expected}")

    migration_value = extract_roadmap_value(roadmap_content, "数据库迁移")
    if migration_value and expected_migration_range and expected_migration_range not in migration_value:
        result.issues.append(
            f"ROADMAP migration range mismatch: documented='{migration_value}', expected range contains '{expected_migration_range}'"
        )

    return result



def print_result(result: CheckResult) -> None:
    """Print human-readable check result with concise pass/fail details."""
    if result.ok():
        print(f"[PASS] {result.name}")
        return
    print(f"[FAIL] {result.name}: {len(result.issues)} issue(s)")
    for issue in result.issues:
        print(f"  - {issue}")



def main() -> int:
    """Run selected consistency checks and return process exit code."""
    args = parse_args()
    root = get_repo_root()

    requested_checks = []
    if args.check_routes:
        requested_checks.append("routes")
    if args.check_env:
        requested_checks.append("env")
    if args.check_roadmap:
        requested_checks.append("roadmap")
    if not requested_checks:
        requested_checks = ["routes", "env", "roadmap"]

    openapi_path = args.openapi
    if not openapi_path.is_absolute():
        openapi_path = root / openapi_path

    results: list[CheckResult] = []
    if "routes" in requested_checks:
        results.append(check_routes(root, openapi_path))
    if "env" in requested_checks:
        results.append(check_env_vars(root))
    if "roadmap" in requested_checks:
        results.append(check_roadmap(root))

    for result in results:
        print_result(result)

    return 0 if all(result.ok() for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
