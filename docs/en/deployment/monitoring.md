# Monitoring

Monitoring, metrics, and health checks for RecordPlatform.

## Health Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Overall health status |
| `/actuator/health/liveness` | Kubernetes liveness probe |
| `/actuator/health/readiness` | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics |
| `/actuator/circuitbreakers` | Circuit breaker status |

## Health Check Components

The `/actuator/health` endpoint includes:

| Component | Checks |
|-----------|--------|
| `db` | MySQL connectivity |
| `redis` | Redis connectivity |
| `rabbit` | RabbitMQ connectivity |
| `s3Storage` | S3 node availability |
| `saga` | Saga transaction health |
| `outbox` | Outbox event health |
| `encryption` | Encryption strategy status |

### Sample Response

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "s3Storage": {
      "status": "UP",
      "details": {
        "healthyNodes": 3,
        "totalNodes": 3
      }
    },
    "encryption": {
      "status": "UP",
      "details": {
        "algorithm": "ChaCha20-Poly1305",
        "likelyHasAesNi": true
      }
    }
  }
}
```

## Key Metrics

### Saga Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `saga_total` | Counter | Total Saga count by status |
| `saga_duration` | Timer | Execution/compensation duration |
| `saga_running` | Gauge | Currently running Sagas |
| `saga_pending_compensation` | Gauge | Sagas awaiting compensation |

### Outbox Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `outbox_events_total` | Counter | Events by status (published/failed) |
| `outbox_publish_latency` | Timer | Event publish latency |
| `outbox_pending` | Gauge | Pending events |
| `outbox_exhausted` | Gauge | Events exceeding max retries |

### Storage Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `s3_node_online_status` | Gauge | Node online status (0/1), bridged via backend actuator from storage capacity snapshots |
| `s3_node_usage_percent` | Gauge | Node disk usage percent (0-100), bridged via backend actuator from storage capacity snapshots |

## Health Thresholds

Configure alerting thresholds:

```yaml
# Outbox thresholds
outbox:
  health:
    pending-threshold: 500    # >500 pending → DEGRADED
    failed-threshold: 20      # >20 failed → DOWN

# Saga thresholds
saga:
  health:
    running-threshold: 100    # >100 running → DEGRADED
    failed-threshold: 10      # >10 failed → DOWN
    pending-compensation-threshold: 50  # >50 pending → DEGRADED
```

## Prometheus Configuration

### Scrape Config

```yaml
scrape_configs:
  - job_name: 'recordplatform-backend'
    metrics_path: '/record-platform/actuator/prometheus'
    static_configs:
      - targets: ['backend:8000']

  # Storage and FISCO are Dubbo providers without embedded HTTP servers,
  # so they do not expose /actuator/prometheus directly.
  # Collect their metrics via the OTel Collector Prometheus exporter instead.
  - job_name: 'otel-collector'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['otel-collector:8889']
```

### Alert Rules

```yaml
groups:
  - name: recordplatform
    rules:
      - alert: SagaFailureHigh
        expr: saga_total{status="failed"} > 10
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High Saga failure rate"

      - alert: OutboxBacklog
        expr: outbox_pending > 500
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Outbox event backlog"

      - alert: S3NodeDown
        expr: s3_node_online_status == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "S3 storage node offline"
```

## Grafana Dashboards

### Recommended Panels

1. **System Overview**
   - Request rate and latency
   - Error rate
   - Active connections

2. **Saga Status**
   - Running vs completed vs failed
   - Compensation queue depth
   - Average duration

3. **Storage Health**
   - Node status per domain
   - Replication lag
   - Disk usage

4. **Blockchain**
   - Transaction rate
   - Circuit breaker status
   - Retry counts

## Distributed Tracing (OpenTelemetry)

The project integrates OpenTelemetry Java Agent v2.26.1 for automatic trace and metrics collection across all three Java services.

### Infrastructure

| Component | Port | Description |
|-----------|------|-------------|
| OTel Collector | 4317 (gRPC), 4318 (HTTP), 8889 (Prometheus) | Trace and metrics pipeline |
| Jaeger | 16686 | Tracing visualization UI |

### Enabling

**Docker deployment**: Set `OTEL_JAVAAGENT_ENABLED=true` (enabled by default)

**Local development**:

```bash
./scripts/start.sh start --otel all
```

> `--otel` and `--skywalking` are mutually exclusive and cannot be enabled simultaneously.

### Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Collector endpoint |
| `OTEL_TRACES_SAMPLER` | `parentbased_traceidratio` | Sampling strategy |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` | Sampling rate (10%) |

Uses W3C Trace Context propagation for cross-service distributed tracing.

### Jaeger UI

Visit http://localhost:16686 to view trace data.

## Storage Integrity Check

The system periodically verifies the consistency of S3-stored files against blockchain records.

### How It Works

1. Runs daily at 2:00 AM, sampling 1% of files
2. Verifies file existence in S3
3. Compares database hash against on-chain hash
4. Creates alerts and notifies admins via SSE when anomalies are found

> Due to storage-layer encryption, the check does not re-hash file contents. Instead it verifies S3 file existence + DB-to-chain hash consistency.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `integrity.check.enabled` | `true` | Enable/disable check |
| `integrity.check.cron` | `0 0 2 * * ?` | Execution schedule |
| `integrity.check.sample-rate` | `0.01` | Sampling rate |
| `integrity.check.batch-size` | `50` | Batch size |

### Admin Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/admin/integrity-alerts` | List integrity alerts |
| `POST /api/v1/admin/integrity-alerts/check` | Trigger manual check |
| `PUT /api/v1/admin/integrity-alerts/{id}/acknowledge` | Acknowledge an alert |
| `PUT /api/v1/admin/integrity-alerts/{id}/resolve` | Resolve an alert |

### Alert Notifications

When integrity anomalies are detected, the system pushes `INTEGRITY_ALERT` events to admins via SSE. Alert records are stored in the `integrity_alert` table. A distributed lock (Redisson) ensures no concurrent check executions.

## SkyWalking Integration

### Configuration

The deployment scripts automatically configure SkyWalking agent:

```bash
SKYWALKING_OPTS="-javaagent:/path/to/skywalking-agent.jar \
  -Dskywalking.agent.service_name=platform-backend \
  -Dskywalking.collector.backend_service=skywalking-oap:11800"
```

### Distributed Tracing

SkyWalking provides:
- Request tracing across services
- Slow query detection
- Service dependency mapping
- Error tracking

## Log Aggregation

### ELK Stack Configuration

```yaml
# Logstash pipeline
input {
  beats {
    port => 5044
  }
}

filter {
  if [fields][service] == "recordplatform" {
    grok {
      match => { "message" => "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:msg}" }
    }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "recordplatform-%{+YYYY.MM.dd}"
  }
}
```

## SLO/SLI Observability

### Service Level Indicators (SLI)

| SLI | Metric Source | Calculation |
|-----|--------------|-------------|
| **Upload Success Rate** | `saga_total_total{status}` | completed / (completed + failed + compensated) |
| **Attestation P99 Latency** | `otel_blockchain_operation_duration_seconds{quantile="0.99"}` | `max_over_time(...[window])` over collector-exported P99 samples |
| **Storage Availability** | `s3_node_online_status` | 30-day rolling average of the deduplicated online-node ratio (`max by (node, fault_domain)`) |
| **API Error Rate** | `http_server_requests_seconds_count{status}` | 5xx count / total count |

### Service Level Objectives (SLO)

| SLO | Target | Window | Error Budget (30d) |
|-----|--------|--------|--------------------|
| Upload Success Rate | >= 99.5% | 30-day rolling | 0.5% (~216 min) |
| Attestation P99 Latency | <= 5s | 30-day rolling | — |
| Storage Availability | >= 99.9% | 30-day rolling | 0.1% (~43 min) |
| API Error Rate | <= 0.5% | 30-day rolling | 0.5% (~216 min) |

### Burn-Rate Alerting

Uses the Google SRE multi-window burn-rate model. Both short AND long windows must fire simultaneously to reduce false positives.

| Severity | Short Window | Long Window | Burn Rate | Action |
|----------|-------------|-------------|-----------|--------|
| **Critical** | 5 min | 1 hour | 14.4x | Page immediately |
| **Warning** | 30 min | 6 hours | 6x | Same-day ticket |
| **Info** | 1 hour | 1 day | 3x | Review next week |

### Configuration Files

| File | Purpose |
|------|---------|
| `config/prometheus/recording-rules.yml` | SLI pre-computation at multiple time windows |
| `config/prometheus/alerting-rules.yml` | Burn-rate alerts + error budget exhaustion alerts |
| `config/grafana/slo-dashboard.json` | Grafana v10+ SLO overview dashboard |

Load in Prometheus via:

```yaml
rule_files:
  - "config/prometheus/recording-rules.yml"
  - "config/prometheus/alerting-rules.yml"
```

### Grafana Dashboard

Import `config/grafana/slo-dashboard.json` into Grafana. The dashboard includes:

| Row | Content |
|-----|---------|
| SLO Overview | 4 stat panels showing current SLI values vs targets |
| Error Budget | Upload and API error budget remaining gauges |
| Upload Success | Time series with 99.5% SLO threshold line |
| Attestation Latency | P50/P95/P99 time series with 5s threshold |
| Storage Availability | 30-day rolling availability ratio + per-node status table |
| API Error Rate | Error rate time series + top-5 error endpoints |
| Resilience4j | Circuit breaker states + retry counts |

> **Note:** Attestation latency is scraped from the OTel Collector Prometheus exporter, so the metric carries the collector namespace prefix (`otel_`). It still uses Micrometer pre-computed client-side quantiles (`.publishPercentiles()`), so the SLO rules roll up window-specific values with `max_over_time(...)` instead of `histogram_quantile(...)`. These quantiles are not aggregatable across multiple service instances. For multi-instance deployments, add `.publishPercentileHistogram(true)` to `FiscoMetrics.java` timer builders.
