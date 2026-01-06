#!/usr/bin/env python3
import os
import sys
import time
from dataclasses import dataclass
from typing import Optional

try:
    from daytona import Daytona, DaytonaConfig, CreateSandboxParams, Resources
except ImportError:
    print("❌ Daytona SDK not installed. Run: pip install daytona")
    sys.exit(1)

SNAPSHOT_NAME = "recordplatform-test-env"
REPO_URL = "https://github.com/SoarCollab/RecordPlatform.git"


@dataclass
class TestResults:
    backend_passed: bool
    frontend_passed: bool
    duration: float
    backend_coverage: Optional[str] = None
    frontend_coverage: Optional[str] = None


def create_snapshot():
    print("🏗️  Creating RecordPlatform test snapshot...\n")

    api_key = os.getenv("DAYTONA_API_KEY")
    if not api_key:
        print("❌ DAYTONA_API_KEY environment variable is required")
        print("\nGet your API key from: https://app.daytona.io/dashboard/api-keys")
        print('Then: export DAYTONA_API_KEY="your-key-here"')
        sys.exit(1)

    config = DaytonaConfig(api_key=api_key, target=os.getenv("DAYTONA_TARGET", "us"))
    daytona = Daytona(config)

    try:
        params = CreateSandboxParams(
            snapshot="docker:28.3.3-dind", resources=Resources(cpu=4, memory=8, disk=10)
        )
        sandbox = daytona.create(params)
        print(f"✓ Sandbox created: {sandbox.id}")

        sandbox.git.clone(url=REPO_URL, path="/workspace/project")
        print("✓ Repository cloned")

        build_script = """
set -e
cd /workspace/project

echo "Installing platform-api..."
mvn -f platform-api/pom.xml clean install -DskipTests -q

echo "Caching backend dependencies..."
mvn -f platform-backend/pom.xml dependency:go-offline -q
mvn -f platform-backend/pom.xml clean verify -DskipTests -q

echo "Caching frontend dependencies..."
cd platform-frontend
pnpm install --frozen-lockfile

echo "✓ Snapshot preparation complete"
        """

        result = sandbox.process.execute_command(build_script, "/workspace")

        if result.exit_code != 0:
            raise Exception(f"Snapshot preparation failed: {result.result}")

        print("✓ Dependencies cached")

        sandbox.create_snapshot(SNAPSHOT_NAME)

        print(f"\n✅ Snapshot '{SNAPSHOT_NAME}' created successfully!")
        print("\nNext steps:")
        print(f"  python {__file__} run-tests")

        sandbox.delete()

    except Exception as error:
        print(f"❌ Snapshot creation failed: {error}")
        sys.exit(1)


def run_tests() -> TestResults:
    print("🧪 Running RecordPlatform tests from snapshot...\n")

    api_key = os.getenv("DAYTONA_API_KEY")
    if not api_key:
        print("❌ DAYTONA_API_KEY environment variable is required")
        sys.exit(1)

    config = DaytonaConfig(api_key=api_key, target=os.getenv("DAYTONA_TARGET", "us"))
    daytona = Daytona(config)

    sandbox = None
    start_time = time.time()

    try:
        params = CreateSandboxParams(
            snapshot=SNAPSHOT_NAME,
            resources=Resources(cpu=4, memory=8, disk=10),
            ephemeral=True,
            auto_stop_interval=30,
        )
        sandbox = daytona.create(params)
        print(f"✓ Sandbox created from snapshot: {sandbox.id}\n")

        update_result = sandbox.process.execute_command(
            "cd /workspace/project && git pull origin main", "/workspace"
        )

        if update_result.exit_code == 0:
            print("✓ Repository updated\n")

        print("Running backend tests...")
        backend_result = sandbox.process.execute_command(
            """
cd /workspace/project
mvn -f platform-backend/pom.xml clean verify -pl backend-service,backend-web -am -Pit
            """,
            "/workspace",
        )

        backend_passed = backend_result.exit_code == 0
        print(
            "✅ Backend tests PASSED" if backend_passed else "❌ Backend tests FAILED"
        )

        print("\nRunning frontend tests...")
        frontend_result = sandbox.process.execute_command(
            """
cd /workspace/project/platform-frontend
pnpm install --frozen-lockfile
pnpm test:coverage
            """,
            "/workspace",
        )

        frontend_passed = frontend_result.exit_code == 0
        print(
            "✅ Frontend tests PASSED"
            if frontend_passed
            else "❌ Frontend tests FAILED"
        )

        backend_coverage = None
        frontend_coverage = None

        try:
            backend_coverage = sandbox.fs.download_file(
                "/workspace/project/platform-backend/backend-web/target/site/jacoco/jacoco.xml"
            )
        except Exception:
            pass

        try:
            frontend_coverage = sandbox.fs.download_file(
                "/workspace/project/platform-frontend/coverage/lcov.info"
            )
        except Exception:
            pass

        duration = time.time() - start_time

        results = TestResults(
            backend_passed=backend_passed,
            frontend_passed=frontend_passed,
            duration=duration,
            backend_coverage="Downloaded" if backend_coverage else None,
            frontend_coverage="Downloaded" if frontend_coverage else None,
        )

        print("\n" + "=" * 50)
        print("Test Summary:")
        print("=" * 50)
        print(f"Backend:  {'✅ PASSED' if backend_passed else '❌ FAILED'}")
        print(f"Frontend: {'✅ PASSED' if frontend_passed else '❌ FAILED'}")
        print(f"Duration: {round(duration)}s")
        print("=" * 50)

        if backend_coverage:
            print("\n📊 Backend coverage report downloaded")
        if frontend_coverage:
            print("📊 Frontend coverage report downloaded")

        return results

    except Exception as error:
        print(f"❌ Test execution failed: {error}")
        raise
    finally:
        if sandbox:
            print("\n🧹 Cleaning up sandbox...")
            sandbox.delete()


def main():
    if len(sys.argv) < 2:
        print("RecordPlatform Daytona Test Runner (Python SDK)\n")
        print("Usage:")
        print(f"  python {__file__} create-snapshot  - Create optimized snapshot")
        print(f"  python {__file__} run-tests        - Run tests from snapshot")
        print("\nPrerequisites:")
        print('  export DAYTONA_API_KEY="your-key"')
        print("  pip install daytona")
        sys.exit(1)

    command = sys.argv[1]

    if command == "create-snapshot":
        create_snapshot()
    elif command == "run-tests":
        results = run_tests()
        sys.exit(0 if results.backend_passed and results.frontend_passed else 1)
    else:
        print(f"Unknown command: {command}")
        sys.exit(1)


if __name__ == "__main__":
    main()
