"""Verify every published release alias with a credential-free Docker pull."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REQUIRED_COMPONENTS = {"backend", "fisco", "storage", "frontend", "verifier"}
DIGEST = r"sha256:[0-9a-f]{64}"
REMEDIATION = (
    "Check package visibility and registry availability. New GHCR packages default "
    "to private: an administrator must review and change the whole package to Public "
    "in GitHub Package settings (including historical versions; irreversible). "
    "Then rerun only the failed publish job while the original release-bundle exists; "
    "do not move the Git tag or rebuild/replace an existing release. "
    "See docs/en/deployment/container-release.md."
)


class VerificationError(RuntimeError):
    """Represent an incomplete or unsafe public release verification."""


def load_references(directory: Path, owner: str) -> list[str]:
    """Validate required artifact sets and collect every generated release alias."""
    files = sorted(directory.glob("*.tags"))
    missing = REQUIRED_COMPONENTS - {file.stem for file in files}
    if missing:
        raise VerificationError(f"Missing tag files: {', '.join(sorted(missing))}")
    pattern = re.compile(
        rf"ghcr\.io/{re.escape(owner.lower())}/recordplatform-[a-z0-9-]+:"
        r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"
    )
    references: list[str] = []
    repositories: set[str] = set()
    for file in files:
        if file.is_symlink() or not file.is_file():
            raise VerificationError(f"Invalid tag file: {file.name}")
        archive = file.with_suffix(".tar")
        if archive.is_symlink() or not archive.is_file() or archive.stat().st_size == 0:
            raise VerificationError(f"Missing or empty image archive: {archive.name}")
        lines = file.read_text(encoding="utf-8").splitlines()
        if not lines or any(not pattern.fullmatch(line) for line in lines):
            raise VerificationError(f"Empty or invalid references in {file.name}")
        package = "verifier-web" if file.stem == "verifier" else file.stem
        expected_repository = f"ghcr.io/{owner.lower()}/recordplatform-{package}"
        if any(line.rsplit(":", 1)[0] != expected_repository for line in lines):
            raise VerificationError(f"Package/tag-file mismatch in {file.name}")
        if expected_repository in repositories or set(references).intersection(lines):
            raise VerificationError(f"Duplicate release references in {file.name}")
        if len(lines) != len(set(lines)):
            raise VerificationError(f"Duplicate release references in {file.name}")
        repositories.add(expected_repository)
        references.extend(lines)
    return references


def docker_command(arguments: list[str], config: Path, timeout: int) -> str:
    """Run Docker against the local runner without inherited login or token state."""
    environment = {
        "PATH": os.environ.get("PATH", os.defpath),
        "HOME": str(config),
        "DOCKER_CONFIG": str(config),
        "LANG": "C",
        "LC_ALL": "C",
    }
    try:
        result = subprocess.run(
            ["docker", "--config", str(config), *arguments],
            env=environment,
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (OSError, subprocess.SubprocessError) as error:
        # Do not echo environment, registry responses, or credential-helper output.
        raise VerificationError(f"Docker {arguments[0]} failed or timed out") from error
    return result.stdout


def inspect_image(reference: str, config: Path) -> tuple[str, str]:
    """Resolve the exact local image identity and its one pushed repository digest."""
    output = docker_command(["image", "inspect", reference], config, 30)
    try:
        images = json.loads(output)
        if not isinstance(images, list) or len(images) != 1:
            raise ValueError("Expected one image")
        image = images[0]
        image_id = image["Id"]
        repository = reference.split("@", 1)[0].split(":", 1)[0]
        digests = {
            value[len(repository) + 1 :]
            for value in image["RepoDigests"]
            if value.startswith(repository + "@")
        }
        if not re.fullmatch(DIGEST, image_id) or len(digests) != 1:
            raise ValueError("Ambiguous local identity")
        digest = digests.pop()
        if not re.fullmatch(DIGEST, digest):
            raise ValueError("Invalid digest")
    except (KeyError, TypeError, ValueError, AttributeError) as error:
        raise VerificationError(
            f"Missing or ambiguous pushed identity for {reference}"
        ) from error
    return image_id, digest


def verify_images(directory: Path, owner: str) -> None:
    """Snapshot all pushed images, then retry bounded anonymous pulls of every alias."""
    references = load_references(directory, owner)
    with tempfile.TemporaryDirectory(prefix="recordplatform-public-pull-") as temporary:
        config = Path(temporary)
        # Explicit auths disables platform-default credential helper discovery too.
        (config / "config.json").write_text(
            '{"auths": {"ghcr.io": {}}}\n', encoding="utf-8"
        )
        expected = {
            reference: inspect_image(reference, config) for reference in references
        }
        verify_references(expected, config)


def verify_references(expected: dict[str, tuple[str, str]], config: Path) -> None:
    """Pull every alias against identities captured before any mutable-tag retrieval."""
    for reference, identity in expected.items():
        for attempt in range(1, 4):
            try:
                output = docker_command(["pull", reference], config, 180)
                pulled = re.findall(rf"(?m)^Digest: ({DIGEST})\s*$", output)
                if pulled != [identity[1]]:
                    raise VerificationError(f"Manifest digest mismatch for {reference}")
                if inspect_image(reference, config) != identity:
                    raise VerificationError(
                        f"Pulled image identity mismatch for {reference}"
                    )
            except VerificationError as error:
                if attempt == 3:
                    raise VerificationError(
                        f"{reference}: {error}. {REMEDIATION}"
                    ) from error
                time.sleep(5)
            else:
                print(f"Anonymous pull verified: {reference}@{identity[1]}")
                break


def verify_snapshot(snapshot: Path, owner: str) -> None:
    """Load immutable public images before verifying historical aliases on a fresh runner."""
    try:
        data = json.loads(snapshot.read_text(encoding="utf-8"))
        if (
            set(data) != {"tags", "images"}
            or set(data["images"]) != REQUIRED_COMPONENTS
        ):
            raise ValueError("Expected all five release components")
        tags = data["tags"]
        if not isinstance(tags, list) or not tags or len(tags) != len(set(tags)):
            raise ValueError("Expected unique nonempty tags")
        if not all(
            re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag) for tag in tags
        ):
            raise ValueError("Invalid tag")
        if not all(re.fullmatch(DIGEST, digest) for digest in data["images"].values()):
            raise ValueError("Invalid manifest digest")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", owner.lower()):
            raise ValueError("Invalid owner")
    except (TypeError, KeyError, ValueError, AttributeError) as error:
        raise VerificationError("Invalid trusted release snapshot") from error
    with tempfile.TemporaryDirectory(
        prefix="recordplatform-public-snapshot-"
    ) as temporary:
        config = Path(temporary)
        (config / "config.json").write_text(
            '{"auths": {"ghcr.io": {}}}\n', encoding="utf-8"
        )
        expected: dict[str, tuple[str, str]] = {}
        for component, digest in sorted(data["images"].items()):
            package = "verifier-web" if component == "verifier" else component
            repository = f"ghcr.io/{owner.lower()}/recordplatform-{package}"
            immutable = f"{repository}@{digest}"
            docker_command(["pull", immutable], config, 180)
            identity = inspect_image(immutable, config)
            if identity[1] != digest:
                raise VerificationError(
                    f"Trusted snapshot digest mismatch for {immutable}"
                )
            expected.update({f"{repository}:{tag}": identity for tag in tags})
        verify_references(expected, config)


def main() -> int:
    """Parse release artifact inputs and return a blocking CI status."""
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--images-dir", type=Path)
    inputs.add_argument("--snapshot", type=Path)
    parser.add_argument("--owner", required=True)
    arguments = parser.parse_args()
    try:
        if arguments.snapshot:
            verify_snapshot(arguments.snapshot, arguments.owner)
        else:
            verify_images(arguments.images_dir, arguments.owner)
    except (OSError, UnicodeError, VerificationError) as error:
        print(f"Public image verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
