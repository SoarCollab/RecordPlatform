"""Exercise lifecycle commands in an isolated release with controlled processes/probes."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest


ROOT = Path(__file__).resolve().parents[3]


class ServiceReadinessTest(unittest.TestCase):
    """Run the actual shell entrypoint without Java services or infrastructure."""

    def setUp(self) -> None:
        """Create a secret-free release and instrument only Java and HTTP boundaries."""
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for directory in ("bin", "jars", "run", "fake"):
            (self.root / directory).mkdir()
        for name in ("start.sh", "env.sh"):
            shutil.copyfile(ROOT / "scripts" / name, self.root / "bin" / name)
        self.env = {
            "PATH": f"{self.root / 'fake'}:{os.environ['PATH']}",
            "RECORD_PLATFORM_SKIP_DOTENV": "true",
            "HEALTH_CHECK_TIMEOUT": "2",
            "HEALTH_CHECK_INTERVAL": "1",
            "HEALTH_CHECK_REQUEST_TIMEOUT": "1",
            "SSL_ENABLED": "false",
            "PROBE_LOG": str(self.root / "probes.jsonl"),
            "JAVA_LOG": str(self.root / "java.jsonl"),
            "PROBE_BODY": "true",
        }
        self.processes: list[subprocess.Popen] = []
        self.write_executable("java", '''#!/usr/bin/env python3
import json, os, sys, time
with open(os.environ['JAVA_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('JAVA_EXIT'):
    sys.exit(int(os.environ['JAVA_EXIT']))
time.sleep(float(os.environ.get('JAVA_LIFETIME', '60')))
''')
        self.write_executable("curl", '''#!/usr/bin/env python3
import json, os, sys, time
with open(os.environ['PROBE_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('PROBE_HANG'):
    time.sleep(float(sys.argv[sys.argv.index('--max-time') + 1]))
    sys.exit(28)
if os.environ.get('PROBE_FAIL_URL', '!') in sys.argv[-1]:
    sys.exit(22)
print(os.environ['PROBE_BODY'])
print('200')
''')
        for service in ("storage", "fisco", "backend"):
            self.jar(service).touch()

    def tearDown(self) -> None:
        """Stop only fixture-owned processes before removing their temporary release."""
        for pid_file in (self.root / "run").glob("*.pid"):
            try:
                pid = int(pid_file.read_text())
                if pid != os.getpid():
                    os.kill(pid, 15)
            except (ValueError, ProcessLookupError):
                pass
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=5)
        self.temp.cleanup()

    def write_executable(self, name: str, content: str) -> None:
        """Install one deterministic boundary fixture."""
        path = self.root / "fake" / name
        path.write_text(content)
        path.chmod(0o755)

    def jar(self, service: str) -> Path:
        """Resolve the same packaged JAR names as env.sh."""
        name = "backend-web" if service == "backend" else f"platform-{service}"
        return self.root / "jars" / f"{name}-0.0.2-SNAPSHOT.jar"

    def running(self, service: str) -> subprocess.Popen:
        """Start an identity-valid process and publish its isolated PID file."""
        process = subprocess.Popen(
            [str(self.root / "fake" / "java"), "-jar", str(self.jar(service))],
            env=self.env,
        )
        self.processes.append(process)
        (self.root / "run" / f"{service}.pid").write_text(str(process.pid))
        return process

    def command(self, *args: str) -> subprocess.CompletedProcess:
        """Invoke the real lifecycle command with a hard test-level deadline."""
        return subprocess.run(
            ["bash", str(self.root / "bin" / "start.sh"), *args],
            env=self.env, text=True, capture_output=True, timeout=12,
        )

    def probes(self) -> list[list[str]]:
        """Read requests recorded by the HTTP fixture."""
        path = self.root / "probes.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_lan_providers_use_configured_loopback_qos(self) -> None:
        """Neither LAN-only Triple endpoint is used for HTTP readiness."""
        self.env.update(DUBBO_HOST="192.0.2.10", DUBBO_STORAGE_PORT="18092",
                        DUBBO_FISCO_PORT="18091", QOS_STORAGE_PORT="32332", QOS_FISCO_PORT="32331")
        self.running("storage")
        self.running("fisco")
        result = self.command("status", "storage", "fisco")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["http://127.0.0.1:32332/ready", "http://127.0.0.1:32331/ready"],
                         [probe[-1] for probe in self.probes()])
        for probe in self.probes():
            for required in ("--max-time", "--connect-timeout", "--fail", "--noproxy"):
                self.assertIn(required, probe)

    def test_backend_profile_context_port_and_host(self) -> None:
        """Use actual prod defaults and explicit operator HTTP overrides."""
        self.running("backend")
        self.env["PROBE_BODY"] = '{"status":"UP"}'
        cases = [({}, "prod", "http://127.0.0.1:443/record-platform/actuator/health"),
                 ({"SSL_ENABLED": "true"}, "prod", "https://127.0.0.1:443/record-platform/actuator/health"),
                 ({"SERVER_PORT": "8800", "SERVER_SERVLET_CONTEXT_PATH": "/custom/", "SERVER_ADDRESS": "192.0.2.11"},
                  "prod", "https://192.0.2.11:8800/custom/actuator/health"),
                 ({"SERVER_SERVLET_CONTEXT_PATH": "", "SERVER_ADDRESS": "0.0.0.0"},
                  "dev", "http://127.0.0.1:8800/actuator/health")]
        for overrides, profile, expected in cases:
            with self.subTest(expected=expected):
                self.env.update(overrides)
                result = self.command("status", "backend", "--profile", profile)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertEqual(expected, self.probes()[-1][-1])

    def test_provider_body_fail_closed(self) -> None:
        """Reject false, malformed, stringified and unrelated success payloads."""
        self.running("storage")
        for body in ("false", '"true"', '{"status":"UP"}', "true garbage", "", "1"):
            with self.subTest(body=body):
                self.env["PROBE_BODY"] = body
                result = self.command("status", "storage")
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("✓", result.stdout)

    def test_backend_requires_top_level_up(self) -> None:
        """A nested UP or HTTP 200 alone must not imply backend health."""
        self.running("backend")
        for body in ('{"status":"DOWN","components":{"x":{"status":"UP"}}}',
                     '{"components":{"x":{"status":"UP"}}}', "true", "UP", "{}"):
            with self.subTest(body=body):
                self.env["PROBE_BODY"] = body
                self.assertNotEqual(0, self.command("status", "backend").returncode)

    def test_existing_unready_process_start_fails(self) -> None:
        """An existing but unready service cannot short-circuit startup success."""
        self.running("storage")
        self.env["PROBE_BODY"] = "false"
        self.assertNotEqual(0, self.command("start", "storage").returncode)

    def test_background_start_success_and_timeout(self) -> None:
        """New-process readiness determines startup status, not process existence."""
        result = self.command("start", "storage")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("启动成功", result.stdout)
        self.env["PROBE_BODY"] = "false"
        result = self.command("start", "fisco")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("启动成功", result.stdout)

    def test_dead_process_and_unrelated_pid_are_not_ready(self) -> None:
        """PID identity protection prevents probing or accepting another process."""
        process = self.running("storage")
        process.terminate()
        process.wait(timeout=5)
        self.assertNotEqual(0, self.command("status", "storage").returncode)
        (self.root / "run" / "storage.pid").write_text(str(os.getpid()))
        self.assertNotEqual(0, self.command("status", "storage").returncode)
        self.assertEqual([], self.probes())
        os.kill(os.getpid(), 0)

    def test_failed_launch_and_foreground_exit(self) -> None:
        """Propagate Java failure and retain foreground exec/profile semantics."""
        self.env["JAVA_EXIT"] = "7"
        self.assertNotEqual(0, self.command("start", "storage").returncode)
        result = self.command("start", "backend", "--foreground", "--profile=dev")
        self.assertEqual(7, result.returncode)
        args = json.loads((self.root / "java.jsonl").read_text().splitlines()[-1])
        self.assertIn("--spring.profiles.active=dev", args)

    def test_aggregate_start_restart_and_status_preserve_failure(self) -> None:
        """A later successful service never masks an earlier service failure."""
        self.jar("storage").unlink()
        for command in ("start", "restart", "status"):
            with self.subTest(command=command):
                result = self.command(command, "storage", "fisco")
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertNotIn("操作完成", result.stdout)

    def test_hanging_request_respects_wait_budget(self) -> None:
        """The request timeout is clamped to the remaining startup budget."""
        self.running("storage")
        self.env.update(PROBE_HANG="true", HEALTH_CHECK_REQUEST_TIMEOUT="30", HEALTH_CHECK_TIMEOUT="1")
        started = time.monotonic()
        self.assertNotEqual(0, self.command("start", "storage").returncode)
        self.assertLess(time.monotonic() - started, 3)
        probe = self.probes()[-1]
        self.assertEqual("1", probe[probe.index("--max-time") + 1])

    def test_http_error_and_invalid_timing_fail(self) -> None:
        """HTTP errors and invalid timing settings cannot produce success."""
        self.running("storage")
        self.env["PROBE_FAIL_URL"] = "/ready"
        self.assertNotEqual(0, self.command("status", "storage").returncode)
        self.env["HEALTH_CHECK_TIMEOUT"] = "0"
        self.assertNotEqual(0, self.command("start", "storage").returncode)

    def test_real_curl_qos_http_semantics(self) -> None:
        """A real loopback HTTP server exercises curl status and body validation."""
        responses = []
        requests = []

        class Handler(BaseHTTPRequestHandler):
            """Serve a controllable QoS-shaped response without a Triple listener."""

            def do_GET(self) -> None:
                """Return the next deterministic test response."""
                requests.append(self.path)
                if self.path == "/redirected-ready":
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b"true")
                    return
                status, body = responses.pop(0)
                self.send_response(status)
                if status == 302:
                    self.send_header("Location", "/redirected-ready")
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format: str, *args: object) -> None:
                """Suppress unrelated HTTP fixture logging."""

        # Move the fake aside so PATH resolves the platform's real curl.
        (self.root / "fake" / "curl").rename(self.root / "fake" / "curl.fixture")
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.running("storage")
        self.env.update(QOS_STORAGE_PORT=str(server.server_port), DUBBO_HOST="192.0.2.10",
                        DUBBO_STORAGE_PORT="1", http_proxy="http://127.0.0.1:1",
                        CURL_HOME=str(self.root))
        (self.root / ".curlrc").write_text(
            "location\nretry = 3\nretry-delay = 1\n", encoding="utf-8"
        )
        try:
            for status, body, ready in ((200, b"true\n", True), (503, b"true", False),
                                        (302, b"true", False), (200, b"false", False),
                                        (200, b"not JSON", False)):
                with self.subTest(status=status, body=body):
                    requests.clear()
                    responses.append((status, body))
                    result = self.command("status", "storage")
                    self.assertEqual(ready, result.returncode == 0, result.stdout)
                    self.assertEqual(["/ready"], requests)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_process_dies_during_startup_wait(self) -> None:
        """A process disappearing after launch fails before the full wait deadline."""
        self.env.update(JAVA_LIFETIME="2", HEALTH_CHECK_TIMEOUT="6", PROBE_BODY="false")
        started = time.monotonic()
        result = self.command("start", "storage")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("启动成功", result.stdout)
        self.assertLess(time.monotonic() - started, 5)

    def test_legacy_ports_and_canonical_precedence(self) -> None:
        """Legacy aliases configure the application, while canonical ports win."""
        self.running("backend")
        self.env.update(BACKEND_PORT="9000", PROBE_BODY='{"status":"UP"}')
        self.assertEqual(0, self.command("status", "backend").returncode)
        self.assertIn(":9000/", self.probes()[-1][-1])
        self.env["SERVER_PORT"] = "9100"
        self.assertEqual(0, self.command("status", "backend").returncode)
        self.assertIn(":9100/", self.probes()[-1][-1])

    def test_unrelated_pid_stop_is_safe(self) -> None:
        """Stop ignores the test runner PID instead of signalling it."""
        (self.root / "run" / "storage.pid").write_text(str(os.getpid()))
        self.assertEqual(0, self.command("stop", "storage").returncode)
        os.kill(os.getpid(), 0)


if __name__ == "__main__":
    unittest.main()
