"""Exercise the shipped shell check against isolated, read-only HTTP fixtures."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[3]
TOKEN = 'synthetic-token-never-output'
PASSWORD = 'synthetic-password-never-output&+= spaced'
CONTENT = 'synthetic-config-secret-never-output'


class NacosEnvironmentCheckTest(unittest.TestCase):
    """Never connect to shared infrastructure or consume a developer dotenv file."""

    def check(self, responses: dict, overrides: dict | None = None, delay: float = 0) -> tuple:
        """Run the real shell script against exact fixture routes and retain requests."""
        requests = []

        class Handler(BaseHTTPRequestHandler):
            """Return deterministic HTTP fixtures without writing server access logs."""

            def handle_request(self):
                """Dispatch GET/form POST and reject any attempted configuration write."""
                parts = urlsplit(self.path)
                params = parse_qs(parts.query, keep_blank_values=True)
                if self.command == 'POST':
                    params = parse_qs(self.rfile.read(int(self.headers['Content-Length'])).decode())
                requests.append((self.command, parts.path, params))
                if delay:
                    time.sleep(delay)
                status, body = responses.get(parts.path, (404, 'unsupported'))
                self.send_response(status)
                if status == 302:
                    self.send_header('Location', '/redirected-credential-target')
                data = (json.dumps(body, indent=2) if isinstance(body, dict) else body).encode()
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except BrokenPipeError:
                    pass  # The deadline fixture intentionally closes the request early.

            do_GET = handle_request
            do_POST = handle_request

            def log_message(self, *_args):
                """Suppress URL/token output even for synthetic test credentials."""

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / 'scripts'
                path.mkdir()
                shutil.copyfile(ROOT / 'scripts/env-check.sh', path / 'env-check.sh')
                env = {'PATH': os.environ['PATH'], 'NACOS_HOST': '127.0.0.1',
                       'NACOS_PORT': str(server.server_port), 'NACOS_USERNAME': 'app',
                       'NACOS_PASSWORD': PASSWORD, **(overrides or {})}
                result = subprocess.run(['bash', str(path / 'env-check.sh'), '--service', 'nacos'],
                                        env=env, text=True, capture_output=True, timeout=15)
                for sentinel in (TOKEN, PASSWORD, CONTENT):
                    self.assertNotIn(sentinel, result.stdout + result.stderr)
                self.assertNotIn('Traceback', result.stderr)
                return result, requests
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def v3(self) -> dict:
        """Return the observed v3 envelope and nested client-result shape."""
        return {
            '/nacos/v3/admin/core/state/readiness': (200, {'code': 0, 'data': 'ok'}),
            '/nacos/v3/auth/user/login': (200, {'accessToken': TOKEN}),
            '/nacos/v3/client/cs/config': (200, {'code': 0, 'data': {
                'success': True, 'resultCode': 200, 'errorCode': 0, 'content': CONTENT}}),
        }

    def test_v3_ready_login_and_configs_use_correct_parameter_names(self) -> None:
        """Validate whitespace JSON, URL encoding, namespace/group and fixed Data IDs."""
        result, requests = self.check(self.v3(), {'NACOS_NAMESPACE': 'team/a & b',
                                                'NACOS_GROUP': 'TEAM & GROUP'})
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(2, result.stdout.count('Config found:'))
        configs = [params for method, path, params in requests if path.endswith('/cs/config')]
        self.assertEqual(['backend-web.yaml', 'platform-storage.yaml'], [p['dataId'][0] for p in configs])
        for params in configs:
            self.assertEqual(['team/a & b'], params['namespaceId'])
            self.assertEqual(['TEAM & GROUP'], params['groupName'])
            self.assertEqual([TOKEN], params['accessToken'])
            self.assertNotIn('group', params)
        self.assertEqual([PASSWORD], requests[1][2]['password'])
        self.assertFalse(any('/v1/' in path for _, path, _ in requests))

    def test_default_empty_and_public_namespaces_normalize_identically(self) -> None:
        """The public namespace alias does not become a missing configuration."""
        for namespace in ('', 'public'):
            result, requests = self.check(self.v3(), {'NACOS_NAMESPACE': namespace})
            self.assertEqual(0, result.returncode)
            self.assertEqual(['public'], requests[-1][2]['namespaceId'])

    def test_nacos2_fallback_requires_version_and_retains_legacy_query_contract(self) -> None:
        """Only an unsupported v3 path plus confirmed older version enables legacy APIs."""
        responses = {
            '/nacos/v3/admin/core/state/readiness': (404, 'unsupported'),
            '/nacos/v1/console/server/state': (200, {'version': '2.4.3'}),
            '/nacos/v1/console/health/readiness': (200, 'OK'),
            '/nacos/v1/auth/login': (200, {'accessToken': TOKEN}),
            '/nacos/v1/cs/configs': (200, CONTENT),
        }
        result, requests = self.check(responses)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual([''], requests[-1][2]['tenant'])
        self.assertEqual(['DEFAULT_GROUP'], requests[-1][2]['group'])
        result, requests = self.check(responses, {'NACOS_NAMESPACE': 'private-namespace'})
        self.assertEqual(0, result.returncode)
        self.assertEqual(['private-namespace'], requests[-1][2]['tenant'])

    def test_v3_auth_server_and_malformed_failures_never_downgrade(self) -> None:
        """Denied, unavailable and malformed v3 responses are failures, not fallback hints."""
        for response in ((403, {'code': 10001}), (500, {'code': 500}),
                         (200, {'code': 0, 'data': False}), (200, '<html>bad</html>'),
                         (200, '{"code":0,"code":1,"data":"ok"}'), (302, 'redirect')):
            with self.subTest(response=response):
                routes = self.v3()
                routes['/nacos/v3/admin/core/state/readiness'] = response
                result, requests = self.check(routes)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(1, len(requests))

    def test_unknown_or_newer_server_404_never_becomes_config_missing(self) -> None:
        """Proxy404 and known Nacos3 cannot silently select a legacy configuration API."""
        for version in ('3.2.4', 'unknown'):
            result, requests = self.check({
                '/nacos/v3/admin/core/state/readiness': (404, 'unsupported'),
                '/nacos/v1/console/server/state': (200, {'version': version}),
            })
            self.assertNotEqual(0, result.returncode)
            self.assertIn('legacy server version not confirmed', result.stdout)
            self.assertEqual(2, len(requests))

    def test_v3_config_missing_and_authorization_are_distinct(self) -> None:
        """A supported API's20004 is absence;403/10001 is denied and never imported."""
        for status, value, expected in (
            (200, {'code': 20004, 'data': None}, 'configuration not found'),
            (404, {'code': 20004, 'data': CONTENT}, 'configuration not found'),
            (403, {'code': 10001, 'data': CONTENT}, 'authorization denied'),
            (200, {'code': 10001, 'data': None}, 'authorization denied'),
            (404, {'code': 404}, 'HTTP failure'),
            (400, {'code': 400}, 'HTTP failure'),
        ):
            with self.subTest(status=status, expected=expected):
                routes = self.v3()
                routes['/nacos/v3/client/cs/config'] = (status, value)
                result, requests = self.check(routes)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stdout)
                self.assertNotIn('Import', result.stdout)
                self.assertFalse(any('/v1/' in path for _, path, _ in requests))

    def test_malformed_nested_success_or_oversize_is_not_found(self) -> None:
        """Empty/error objects and oversized bodies never pass as existing configuration."""
        for body in ({'code': 0, 'data': None}, {'code': 0, 'data': {'success': False}},
                     {'code': 0, 'data': {'success': True, 'resultCode': 200, 'errorCode': 0}},
                     {'code': 0, 'data': {'success': True, 'resultCode': 200, 'errorCode': False, 'content': CONTENT}},
                     CONTENT * 40000):
            routes = self.v3()
            routes['/nacos/v3/client/cs/config'] = (200, body)
            result, _ = self.check(routes)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn('Config found:', result.stdout)

    def test_login_denial_never_queries_configuration(self) -> None:
        """Bad credentials stop before config requests and do not trigger legacy fallback."""
        routes = self.v3()
        routes['/nacos/v3/auth/user/login'] = (403, CONTENT)
        result, requests = self.check(routes)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('authentication denied', result.stdout)
        self.assertEqual(2, len(requests))

    def test_total_request_deadline_stops_stalled_server(self) -> None:
        """A listening but stalled fixture fails within the configured wall-clock limit."""
        started = time.monotonic()
        result, requests = self.check(self.v3(), delay=6)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('timed out', result.stdout)
        self.assertLess(time.monotonic() - started, 8)
        self.assertEqual(1, len(requests))


if __name__ == '__main__':
    unittest.main()
