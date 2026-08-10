#!/usr/bin/env python3
"""Validate documentation consistency against backend/OpenAPI/runtime facts."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree


CORE_WORKFLOWS = ("test.yml", "perf-smoke.yml", "docs.yml", "security-poc.yml", "docs-consistency.yml")
SPRING_BOOT_POMS = (
    "platform-api/pom.xml",
    "platform-verifier/pom.xml",
    "platform-backend/pom.xml",
    "platform-fisco/pom.xml",
    "platform-storage/pom.xml",
)
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
    ("POST", "/api/v1/verify"),
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
DOC_ONLY_ENV_ALLOWLIST = {"JAVA_OPTS", "TENANT_WHITELIST"}

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

DIRECT_UPLOAD_EVIDENCE_PATH = Path("docs/public/evidence/direct-upload-load-smoke-30209115456.json")
DIRECT_UPLOAD_EVIDENCE_SHA256 = "4ecc8b77b7a39f4ccf6c2809dc52642d252bf8d123845823736a8f67476aab39"
DIRECT_UPLOAD_EVIDENCE_DOCS = (
    "ROADMAP.md",
    "TESTING.md",
    "docs/operations/memory-tuning.md",
)
DIRECT_UPLOAD_EVIDENCE_TOKENS = (
    "30209115456",
    "5b9cece769dd3a52cd34a0af45d9342573ad855edb98d048547b732d8cdeab6b",
    "414 ms",
    "291 ms",
    "5,061,808.93 bytes/s",
    "4.83 MiB/s",
    "37,748,736 bytes",
    "36 MiB",
    "2,686,976 bytes",
    "thread delta 23",
    "direct-upload-load-smoke-30209115456.json",
    DIRECT_UPLOAD_EVIDENCE_SHA256,
)
P3_EVIDENCE_DOCS = (
    "docs/en/architecture/delivery-evidence.md",
    "docs/zh/architecture/delivery-evidence.md",
)
P3_EVIDENCE_TOKENS = (
    "P0/P1/P2/P3",
    "cfe9b9e54eefa01246dbddda7ab5a4c27717a3dc",
    "30222751986",
    "30222751977",
    "5614564147",
    "a4ba5acf3864fd341219a7382d13b2cd30d3afde",
    "30235985313",
    "30235985338",
    "5616938788",
    "c0bd8076994ce0cb3bf98a3ff0f722c60ea84a4c",
    "30245935210",
    "30245935295",
    "5618660915",
    "7f9d639f3395269735e9efeb3dbea4e9e025d412",
    "30267453969",
    "30267454033",
    "5622671802",
)
ADVISORY_SNAPSHOT_DOCS = (
    "ROADMAP.md",
    "docs/en/architecture/delivery-evidence.md",
    "docs/zh/architecture/delivery-evidence.md",
)
ADVISORY_SNAPSHOT_TOKENS = (
    "85a57ae847423308cf60683c6fd299d51a1650f1",
    "2026-08-10 14:51 CST",
    "3 open",
    "Maven",
    "Medium",
    "org.apache.commons:commons-lang3",
    "platform-verifier/sdk",
    "platform-verifier/cli-verifier",
    "platform-verifier/web-verifier",
    "3.17.0",
    "3.18.0",
    "https://github.com/SoarCollab/RecordPlatform/security/dependabot",
    "2026-09-30",
)
TEST_SNAPSHOT_COMPONENTS = (
    "platform-backend/backend-common",
    "platform-backend/backend-api",
    "platform-backend/backend-service",
    "platform-backend/backend-web",
    "platform-backend",
    "platform-storage",
    "platform-frontend",
    "platform-verifier",
    "platform-fisco",
    "platform-api",
    "tools/ci",
    "tools/contracts",
    "tools/docs",
    "tools",
    "total",
)
TEST_SNAPSHOT_ROW_PATTERN = re.compile(
    r"^\|\s*`?(platform-(?:backend(?:/(?:backend-common|backend-api|backend-service|backend-web))?"
    r"|storage|frontend|verifier|fisco|api)|tools(?:/(?:ci|contracts|docs))?|total)`?\s*\|\s*(\d+)\s*\|\s*$",
    re.MULTILINE,
)
TEST_SNAPSHOT_HEADING_PATTERN = re.compile(r"^## 当前测试文件快照（(\d+) files）$", re.MULTILINE)
EXCLUDED_TEST_PATH_PARTS = {
    "node_modules",
    ".svelte-kit",
    "target",
    "dist",
    "build",
    "coverage",
    "test-results",
    "playwright-report",
}


@dataclass
class CheckResult:
    """Store check result details for one validation category."""

    name: str
    issues: list[str] = field(default_factory=list)

    def ok(self) -> bool:
        """Return whether the check passed without issues."""
        return not self.issues


@dataclass(frozen=True)
class RuntimeVersions:
    """Store canonical runtime versions collected from build manifests."""

    spring_boot: str
    svelte: str
    sveltekit: str
    vite: str
    tailwind: str



def get_repo_root() -> Path:
    """Resolve repository root based on the script location."""
    return Path(__file__).resolve().parents[2]



def parse_args() -> argparse.Namespace:
    """Parse CLI arguments for selective consistency checks."""
    parser = argparse.ArgumentParser(description="Check documentation consistency.")
    parser.add_argument("--check-routes", action="store_true", help="Validate API routes against OpenAPI")
    parser.add_argument("--check-env", action="store_true", help="Validate documented env vars against code/env sources")
    parser.add_argument("--check-roadmap", action="store_true", help="Validate ROADMAP baseline snapshot")
    parser.add_argument("--check-versions", action="store_true", help="Validate documented runtime versions")
    parser.add_argument(
        "--check-evidence",
        action="store_true",
        help="Validate security, delivery, test-count, and retained-artifact evidence",
    )
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
    return collect_test_file_counts(root)["platform-backend"]


def count_java_test_files(test_root: Path) -> int:
    """Count Java test sources under a canonical src/test root."""
    if not test_root.exists():
        return 0
    return sum(
        1
        for file_path in test_root.rglob("*.java")
        if not EXCLUDED_TEST_PATH_PARTS.intersection(file_path.parts)
        and (file_path.name.endswith("Test.java") or file_path.name.endswith("IT.java"))
    )


def count_frontend_test_files(frontend_root: Path) -> int:
    """Count frontend Vitest and Playwright files while excluding generated outputs."""
    if not frontend_root.exists():
        return 0
    return sum(
        1
        for file_path in frontend_root.rglob("*")
        if file_path.is_file()
        and not EXCLUDED_TEST_PATH_PARTS.intersection(file_path.parts)
        and (file_path.name.endswith(".test.ts") or file_path.name.endswith(".spec.ts"))
    )


def count_python_test_files(test_root: Path) -> int:
    """Count canonical Python unit-test files while excluding generated output trees."""
    if not test_root.exists():
        return 0
    return sum(
        1
        for file_path in test_root.rglob("*.py")
        if file_path.is_file()
        and not EXCLUDED_TEST_PATH_PARTS.intersection(file_path.parts)
        and (file_path.name.startswith("test_") or file_path.name.endswith("_test.py"))
    )


def collect_test_file_counts(root: Path) -> dict[str, int]:
    """Collect the canonical cross-module test-file snapshot from exact source trees."""
    counts = {
        "platform-backend/backend-common": count_java_test_files(
            root / "platform-backend/backend-common/src/test/java"
        ),
        "platform-backend/backend-api": count_java_test_files(root / "platform-backend/backend-api/src/test/java"),
        "platform-backend/backend-service": count_java_test_files(
            root / "platform-backend/backend-service/src/test/java"
        ),
        "platform-backend/backend-web": count_java_test_files(root / "platform-backend/backend-web/src/test/java"),
        "platform-storage": count_java_test_files(root / "platform-storage/src/test/java"),
        "platform-frontend": count_frontend_test_files(root / "platform-frontend"),
        "platform-verifier": count_java_test_files(root / "platform-verifier"),
        "platform-fisco": count_java_test_files(root / "platform-fisco/src/test/java"),
        "platform-api": count_java_test_files(root / "platform-api/src/test/java"),
        "tools/ci": count_python_test_files(root / "tools/ci/tests"),
        "tools/contracts": count_python_test_files(root / "tools/contracts/tests"),
        "tools/docs": count_python_test_files(root / "tools/docs/tests"),
    }
    counts["platform-backend"] = sum(
        counts[key]
        for key in (
            "platform-backend/backend-common",
            "platform-backend/backend-api",
            "platform-backend/backend-service",
            "platform-backend/backend-web",
        )
    )
    counts["tools"] = sum(counts[key] for key in ("tools/ci", "tools/contracts", "tools/docs"))
    counts["total"] = sum(
        counts[key]
        for key in (
            "platform-backend",
            "platform-storage",
            "platform-frontend",
            "platform-verifier",
            "platform-fisco",
            "platform-api",
            "tools",
        )
    )
    return counts



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
    """校验 canonical 路线图、基线计数和旧候选文件引用均未回归。"""
    result = CheckResult("roadmap")
    roadmap_path = root / "ROADMAP.md"
    if not roadmap_path.exists():
        result.issues.append(f"Missing ROADMAP file: {roadmap_path}")
        return result

    legacy_roadmap_path = root / "ROADMAP_new.md"
    if legacy_roadmap_path.exists():
        result.issues.append(
            "Legacy roadmap candidate must not exist; ROADMAP.md is the only canonical source: ROADMAP_new.md"
        )

    reference_paths = [
        root / rel_path
        for rel_path in (
            "README.md",
            "README_CN.md",
            "API_DOCUMENTATION.md",
            "TESTING.md",
            "CONTRIBUTING.md",
            "scripts/README.md",
            "tools/k6/README.md",
        )
    ]
    reference_paths.extend((root / "docs").rglob("*.md"))
    for reference_path in reference_paths:
        if reference_path.exists() and "ROADMAP_new.md" in reference_path.read_text(encoding="utf-8"):
            result.issues.append(
                f"Documentation still references the legacy roadmap candidate: {reference_path.relative_to(root)}"
            )

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
        ("核心工作流", expected_workflows),
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


def direct_child_text(element: ElementTree.Element, name: str) -> str | None:
    """Return the text value of a direct XML child, ignoring namespace prefixes."""
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name:
            return child.text.strip() if child.text else None
    return None


def find_direct_child(element: ElementTree.Element, name: str) -> ElementTree.Element | None:
    """Return a direct XML child element by local tag name."""
    for child in element:
        if child.tag.rsplit("}", 1)[-1] == name:
            return child
    return None


def read_spring_boot_parent_version(pom_path: Path) -> tuple[str | None, str | None]:
    """Read Spring Boot starter parent version from a Maven POM."""
    try:
        project = ElementTree.parse(pom_path).getroot()
    except ElementTree.ParseError as error:
        return None, f"{pom_path} is not valid XML: {error}"

    parent = find_direct_child(project, "parent")
    if parent is None:
        return None, f"{pom_path} does not declare a parent POM"

    group_id = direct_child_text(parent, "groupId")
    artifact_id = direct_child_text(parent, "artifactId")
    version = direct_child_text(parent, "version")
    if group_id != "org.springframework.boot" or artifact_id != "spring-boot-starter-parent":
        return None, f"{pom_path} parent is not spring-boot-starter-parent"
    if version is None:
        return None, f"{pom_path} does not declare Spring Boot parent version"
    return version, None


def parse_package_version(raw_version: str) -> str | None:
    """Extract the first semantic version token from a package version string."""
    match = re.search(r"\d+\.\d+\.\d+", raw_version)
    return match.group(0) if match else None


def minor_floor(version: str) -> str:
    """Convert an exact semantic version into a documented minor floor token."""
    major, minor, _patch = version.split(".", 2)
    return f"{major}.{minor}+"


def read_package_json_versions(package_json_path: Path) -> dict[str, str]:
    """Read selected frontend dependency versions from package.json."""
    payload = json.loads(package_json_path.read_text(encoding="utf-8"))
    dependencies = payload.get("dependencies", {})
    dev_dependencies = payload.get("devDependencies", {})
    packages = {**dependencies, **dev_dependencies}

    selected_packages = {
        "svelte": "svelte",
        "sveltekit": "@sveltejs/kit",
        "vite": "vite",
        "tailwind": "tailwindcss",
    }
    versions: dict[str, str] = {}
    for label, package_name in selected_packages.items():
        raw_version = packages.get(package_name)
        if not isinstance(raw_version, str):
            continue
        parsed = parse_package_version(raw_version)
        if parsed:
            versions[label] = parsed
    return versions


def collect_runtime_versions(root: Path, result: CheckResult) -> RuntimeVersions | None:
    """Collect canonical runtime versions and append collection issues to result."""
    spring_versions: dict[str, str] = {}
    for rel_path in SPRING_BOOT_POMS:
        pom_path = root / rel_path
        version, issue = read_spring_boot_parent_version(pom_path)
        if issue:
            result.issues.append(issue)
            continue
        if version:
            spring_versions[rel_path] = version

    unique_spring_versions = sorted(set(spring_versions.values()))
    if len(unique_spring_versions) != 1:
        details = ", ".join(f"{path}={version}" for path, version in sorted(spring_versions.items()))
        result.issues.append(f"Spring Boot parent version mismatch: {details}")
        return None

    frontend_versions = read_package_json_versions(root / "platform-frontend/package.json")
    missing_frontend = [key for key in ("svelte", "sveltekit", "vite", "tailwind") if key not in frontend_versions]
    for missing in missing_frontend:
        result.issues.append(f"Missing frontend dependency version in package.json: {missing}")

    if not spring_versions or missing_frontend:
        return None

    return RuntimeVersions(
        spring_boot=unique_spring_versions[0],
        svelte=frontend_versions["svelte"],
        sveltekit=frontend_versions["sveltekit"],
        vite=frontend_versions["vite"],
        tailwind=frontend_versions["tailwind"],
    )


def check_doc_contains(root: Path, rel_path: str, expected_tokens: Iterable[str], result: CheckResult) -> None:
    """Check that a documentation file contains every expected version token."""
    file_path = root / rel_path
    if not file_path.exists():
        result.issues.append(f"Version doc target not found: {rel_path}")
        return

    content = file_path.read_text(encoding="utf-8")
    for token in expected_tokens:
        if token not in content:
            result.issues.append(f"{rel_path} does not mention current version token: {token}")


def check_versions(root: Path) -> CheckResult:
    """校验文档中的运行时版本与 POM、package.json 事实一致。"""
    result = CheckResult("versions")
    versions = collect_runtime_versions(root, result)
    if versions is None:
        return result

    spring = versions.spring_boot
    svelte = minor_floor(versions.svelte)
    sveltekit = minor_floor(versions.sveltekit)
    vite = minor_floor(versions.vite)
    tailwind = minor_floor(versions.tailwind)

    doc_expectations = {
        "README.md": (spring, svelte, sveltekit, vite, tailwind),
        "README_CN.md": (spring, svelte, sveltekit, vite, tailwind),
        "ROADMAP.md": (spring, svelte, sveltekit, vite, tailwind),
        "docs/en/getting-started/frontend.md": (svelte, sveltekit, vite, tailwind),
        "docs/zh/getting-started/frontend.md": (svelte, sveltekit, vite, tailwind),
    }
    for rel_path, expected_tokens in doc_expectations.items():
        check_doc_contains(root, rel_path, expected_tokens, result)

    return result


def require_tokens(root: Path, rel_path: str, tokens: Iterable[str], result: CheckResult, label: str) -> None:
    """Require invariant evidence tokens in a documentation file."""
    file_path = root / rel_path
    if not file_path.exists():
        result.issues.append(f"Missing {label} document: {rel_path}")
        return
    content = file_path.read_text(encoding="utf-8")
    for token in tokens:
        if token not in content:
            result.issues.append(f"{rel_path} is missing {label} token: {token}")


def check_api_security_contract(root: Path, result: CheckResult) -> None:
    """Validate the root API guide's encrypted grant-v1 delivery contract."""
    api_path = root / "API_DOCUMENTATION.md"
    if not api_path.exists():
        result.issues.append("Missing API security document: API_DOCUMENTATION.md")
        return

    content = api_path.read_text(encoding="utf-8")
    section_match = re.search(r"(?ms)^### 4\.5 Get File Download Metadata\s*$.*?(?=^### )", content)
    if section_match is None:
        result.issues.append("API_DOCUMENTATION.md is missing section 4.5 download metadata")
        return

    section = section_match.group(0)
    required_section_tokens = (
        '"initialKey": null',
        '"keyGrant": null',
        "X-Key-Delivery-Protocol",
        "X-Download-Session-ID",
        "grant-v1",
        "POST /api/v1/files/key-grants/consume",
        "POST /api/v1/public/key-grants/consume",
        "no-store",
        "plaintext-v0",
    )
    for token in required_section_tokens:
        if token not in section:
            result.issues.append(f"API_DOCUMENTATION.md section 4.5 is missing security token: {token}")

    forbidden_patterns = (
        r"(?i)encrypted files?.{0,80}initialKey.{0,80}(?:contains|returns).{0,80}(?:plain|unwrap|decrypted)",
        r"(?i)initialKey.{0,80}(?:contains|returns).{0,80}(?:plain|unwrap|decrypted).{0,80}encrypted",
    )
    for pattern in forbidden_patterns:
        if re.search(pattern, section, re.DOTALL):
            result.issues.append("API_DOCUMENTATION.md still describes plaintext initialKey for encrypted downloads")
            break

    checklist_match = re.search(r"(?ms)^## 21\. Controller-Aligned Endpoint Checklist\s*$.*", content)
    if checklist_match is None:
        result.issues.append("API_DOCUMENTATION.md is missing controller-aligned endpoint checklist")
        return
    checklist = checklist_match.group(0)
    for route in (
        "POST /api/v1/files/key-grants/consume",
        "POST /api/v1/public/key-grants/consume",
    ):
        if route not in checklist:
            result.issues.append(f"API_DOCUMENTATION.md endpoint checklist is missing: {route}")


def check_delivery_evidence_contract(root: Path, result: CheckResult) -> None:
    """Validate bilingual P3 exact-commit, workflow, and Pages evidence."""
    for rel_path in P3_EVIDENCE_DOCS:
        require_tokens(root, rel_path, P3_EVIDENCE_TOKENS, result, "P3 delivery evidence")


def source_artifact_digest(file_path: Path) -> str:
    """Hash the exact retained artifact bytes."""
    return hashlib.sha256(file_path.read_bytes()).hexdigest()


def nested_json_value(payload: dict[str, object], path: tuple[str, ...]) -> object:
    """Read a required nested JSON value and raise KeyError when evidence is incomplete."""
    current: object = payload
    for key in path:
        if not isinstance(current, dict) or key not in current:
            raise KeyError(".".join(path))
        current = current[key]
    return current


def check_direct_upload_evidence(root: Path, result: CheckResult) -> None:
    """Validate the retained load-smoke artifact identity and every derived summary."""
    evidence_path = root / DIRECT_UPLOAD_EVIDENCE_PATH
    if not evidence_path.exists():
        result.issues.append(f"Missing retained load-smoke evidence: {DIRECT_UPLOAD_EVIDENCE_PATH}")
        return

    digest = source_artifact_digest(evidence_path)
    if digest != DIRECT_UPLOAD_EVIDENCE_SHA256:
        result.issues.append(
            "Retained load-smoke artifact digest mismatch: "
            f"expected={DIRECT_UPLOAD_EVIDENCE_SHA256}, actual={digest}"
        )

    try:
        payload = json.loads(evidence_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        result.issues.append(f"Retained load-smoke artifact is invalid JSON: {error}")
        return

    expected_values: dict[tuple[str, ...], object] = {
        ("schema",): "direct-upload-load-smoke-v1",
        ("environment", "fingerprint"): "5b9cece769dd3a52cd34a0af45d9342573ad855edb98d048547b732d8cdeab6b",
        ("environment", "osName"): "Linux",
        ("environment", "osArchitecture"): "amd64",
        ("environment", "javaVersion"): "21.0.11",
        ("environment", "availableProcessors"): 4,
        ("iterations",): 8,
        ("concurrency",): 4,
        ("partBytes",): 262144,
        ("successes",): 8,
        ("failures",): 0,
        ("wallMillis",): 414,
        ("p99Millis",): 291.0,
        ("bytesPerSecond",): 5061808.932889123,
        ("resource", "heapPeakDeltaBytes"): 37748736,
        ("resource", "threadPeakDelta"): 23,
        ("resource", "directBufferPeakDeltaBytes"): 2686976,
        ("lifecycle", "receiptCountBeforeCleanup"): 8,
        ("lifecycle", "stagingTombstoneCountBeforeCleanup"): 8,
        ("lifecycle", "receiptCountAfterCleanup"): 0,
        ("lifecycle", "stagingTombstoneCountAfterCleanup"): 0,
        ("cleanupSuccess",): True,
    }
    for path, expected in expected_values.items():
        try:
            actual = nested_json_value(payload, path)
        except KeyError:
            result.issues.append(f"Retained load-smoke artifact is missing field: {'.'.join(path)}")
            continue
        if actual != expected:
            result.issues.append(
                f"Retained load-smoke field mismatch for {'.'.join(path)}: expected={expected}, actual={actual}"
            )

    for rel_path in DIRECT_UPLOAD_EVIDENCE_DOCS:
        require_tokens(root, rel_path, DIRECT_UPLOAD_EVIDENCE_TOKENS, result, "load-smoke evidence")


def extract_documented_test_snapshot(content: str) -> dict[str, int]:
    """Extract the machine-checked test snapshot table from TESTING.md."""
    snapshot: dict[str, int] = {}
    for component, value in TEST_SNAPSHOT_ROW_PATTERN.findall(content):
        if component in snapshot:
            raise ValueError(f"duplicate test snapshot row: {component}")
        snapshot[component] = int(value)
    return snapshot


def check_test_snapshot(root: Path, result: CheckResult) -> None:
    """Validate TESTING.md counts against exact canonical source trees."""
    testing_path = root / "TESTING.md"
    if not testing_path.exists():
        result.issues.append("Missing test snapshot document: TESTING.md")
        return
    content = testing_path.read_text(encoding="utf-8")
    try:
        documented = extract_documented_test_snapshot(content)
    except ValueError as error:
        result.issues.append(str(error))
        return

    expected = collect_test_file_counts(root)
    heading_match = TEST_SNAPSHOT_HEADING_PATTERN.search(content)
    if heading_match is None:
        result.issues.append("TESTING.md is missing the machine-checked test snapshot heading")
    elif int(heading_match.group(1)) != expected["total"]:
        result.issues.append(
            "TESTING.md test snapshot heading mismatch: "
            f"documented={heading_match.group(1)}, actual={expected['total']}"
        )
    for component in TEST_SNAPSHOT_COMPONENTS:
        if component not in documented:
            result.issues.append(f"TESTING.md is missing test snapshot row: {component}")
            continue
        if documented[component] != expected[component]:
            result.issues.append(
                f"TESTING.md test snapshot mismatch for {component}: "
                f"documented={documented[component]}, actual={expected[component]}"
            )


def check_readme_env_parity(root: Path, result: CheckResult) -> None:
    """Validate that both root READMEs expose the same executable env-loading sequence."""
    commands = ("cp .env.example .env", "set -a", "source .env", "set +a")
    for rel_path in ("README.md", "README_CN.md"):
        file_path = root / rel_path
        if not file_path.exists():
            result.issues.append(f"Missing README env document: {rel_path}")
            continue
        content = file_path.read_text(encoding="utf-8")
        offsets = [content.find(command) for command in commands]
        if any(offset < 0 for offset in offsets):
            missing = [command for command, offset in zip(commands, offsets, strict=True) if offset < 0]
            result.issues.append(f"{rel_path} is missing env command(s): {', '.join(missing)}")
            continue
        if offsets != sorted(offsets):
            result.issues.append(f"{rel_path} env commands are not in executable order")


def check_advisory_snapshot(root: Path, result: CheckResult) -> None:
    """Validate that dynamic advisory evidence is explicitly time- and SHA-bounded."""
    for rel_path in ADVISORY_SNAPSHOT_DOCS:
        require_tokens(root, rel_path, ADVISORY_SNAPSHOT_TOKENS, result, "advisory snapshot")


def check_evidence(root: Path) -> CheckResult:
    """Validate cross-document security, delivery, artifact, test, and advisory evidence."""
    result = CheckResult("evidence")
    check_api_security_contract(root, result)
    check_delivery_evidence_contract(root, result)
    check_direct_upload_evidence(root, result)
    check_test_snapshot(root, result)
    check_readme_env_parity(root, result)
    check_advisory_snapshot(root, result)
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
    if args.check_versions:
        requested_checks.append("versions")
    if args.check_evidence:
        requested_checks.append("evidence")
    if not requested_checks:
        requested_checks = ["routes", "env", "roadmap", "versions", "evidence"]

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
    if "versions" in requested_checks:
        results.append(check_versions(root))
    if "evidence" in requested_checks:
        results.append(check_evidence(root))

    for result in results:
        print_result(result)

    return 0 if all(result.ok() for result in results) else 1


if __name__ == "__main__":
    sys.exit(main())
