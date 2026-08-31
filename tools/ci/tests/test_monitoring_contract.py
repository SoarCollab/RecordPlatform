"""Lock dashboard queries, idle semantics and the executable monitoring gate."""

import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]


class MonitoringContractTest(unittest.TestCase):
    """Assert source consumers remain aligned with real promtool fixtures."""

    def test_dashboard_latency_queries_have_numerical_fixtures(self) -> None:
        """Every actual latency panel query must be exercised by the rule engine."""
        dashboard = json.loads((ROOT / 'config/grafana/slo-dashboard.json').read_text())
        self.assertEqual('record-platform-slo', dashboard['uid'])
        panel = next(p for p in dashboard['panels'] if p['id'] == 12)
        fixture = (ROOT / 'config/prometheus/tests/slo-rules.test.yml').read_text()
        for target in panel['targets']:
            self.assertIn("- expr: '" + target['expr'] + "'", fixture)
            self.assertIn('exported_job="record-platform-fisco"', target['expr'])
        self.assertNotIn('quantile=', json.dumps(panel))
        self.assertEqual('s', panel['fieldConfig']['defaults']['unit'])

    def test_current_tiles_do_not_reuse_stale_good_values(self) -> None:
        """Current stat/gauge tiles show latest values and explicit unknown text."""
        dashboard = json.loads((ROOT / 'config/grafana/slo-dashboard.json').read_text())
        self.assertIn('not healthy', dashboard['description'])
        for panel in dashboard['panels']:
            if panel['type'] not in ('stat', 'gauge'):
                continue
            with self.subTest(panel=panel['id']):
                self.assertEqual(['last'], panel['options']['reduceOptions']['calcs'])
                self.assertIn('Unknown', panel['fieldConfig']['defaults']['noValue'])
                self.assertTrue(all(t.get('instant') for t in panel['targets']))

    def test_rule_engine_gate_is_pinned_offline_and_required(self) -> None:
        """CI cannot turn missing tooling into a skipped success or drift its image."""
        script = (ROOT / 'tools/ci/check-monitoring.sh').read_text()
        self.assertIn('prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0', script)
        self.assertIn('--network none', script)
        self.assertIn('readonly', script)
        self.assertIn('run_promtool test rules config/prometheus/tests/slo-rules.test.yml', script)
        self.assertNotIn('|| true', script)
        workflow = (ROOT / '.github/workflows/test.yml').read_text()
        required = workflow.split('\n  required-ci:', 1)[1]
        self.assertIn('run: bash tools/ci/check-monitoring.sh', required)
        self.assertNotRegex(required, r'if:.*monitoring')

    def test_bilingual_docs_and_template_document_provider_default(self) -> None:
        """Docs retain histogram, configured-job and unknown observation semantics."""
        for language in ('en', 'zh'):
            content = (ROOT / f'docs/{language}/deployment/monitoring.md').read_text()
            self.assertIn('OTEL_INSTRUMENTATION_MICROMETER_ENABLED', content)
            self.assertIn('otel_blockchain_operation_duration_seconds_bucket', content)
            self.assertIn('RecordPlatformScrapeTargetDown', content)
            self.assertIn('bash tools/ci/check-monitoring.sh', content)
        template = (ROOT / '.env.example').read_text()
        self.assertIsNone(re.search(r'^OTEL_INSTRUMENTATION_MICROMETER_ENABLED=true$', template, re.M))


if __name__ == '__main__':
    unittest.main()
