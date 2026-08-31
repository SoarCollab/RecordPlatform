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

### Production Attestation Batch Metrics

All labels are fixed enumerations. Tenant, file, candidate, and batch identifiers are deliberately excluded to keep metric cardinality bounded.

| Metric | Type | Labels / Description |
|--------|------|----------------------|
| `app_attestation_candidate_total` | Counter | `result=admitted\|batched\|dead_letter` |
| `app_attestation_candidate_backlog` | Gauge | Last scheduled global observation, `status=ready\|dead_letter` |
| `app_attestation_batch_total` | Counter | `status=completed\|retry\|manual_review` |
| `app_attestation_batch_size` | Distribution summary | Manifest-evidence leaves per created batch |
| `app_attestation_batch_latency_seconds` | Timer | Candidate admission to local batch creation |
| `app_attestation_production_run_total` | Counter | `result=completed\|disabled\|failed` |

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

### Dedicated Scrape Identity

Backend metrics are **not anonymous**. The optional machine identity is disabled by
default; existing admin/monitor Bearer access still requires the matching business
tenant. Enable the machine path explicitly with these backend settings:

| Environment variable | Application property | Default |
|---|---|---|
| `PROMETHEUS_SCRAPE_ENABLED` | `security.prometheus-scrape.enabled` | `false` |
| `PROMETHEUS_SCRAPE_USERNAME` | `security.prometheus-scrape.username` | empty |
| `PROMETHEUS_SCRAPE_PASSWORD_HASH` | `security.prometheus-scrape.password-hash` | empty |

Use a dedicated username (1–64 ASCII letters/digits, dot, underscore or hyphen;
first character must be alphanumeric) and a BCrypt hash (`$2a$`, `$2b$`, or `$2y$`).
Enabled configuration with missing or invalid values fails startup; plaintext and
`{noop}` passwords are rejected. Only exact `GET`/`HEAD /actuator/prometheus`, below
the configured servlet context path, accept this Basic identity. It has only
`PROMETHEUS_SCRAPE`, **not** an admin/monitor business role. It cannot authenticate
files, audit/log APIs, other actuator endpoints, descendants, or write methods.
Caller tenant headers are ignored on this machine route and create no business
tenant context. Do not add an invented `X-Tenant-ID` header to the scraper.

Provision a strong random password in a local secret manager, then store it in a
private Prometheus `password_file` (owner-only `0600`, containing only the password,
with no trailing newline). Generate a BCrypt hash interactively using a trusted
tool such as `htpasswd -cB -C 12 /private/path/scrape.htpasswd collector`; enter the
same password at its prompt, never pass it with `-b`. Store **only the hash** in the
backend's private configuration, never the `username:` prefix. Protect any hash
file too. Username/hash/password files and certificates must not be committed.

Single-quote the complete hash in shell or dotenv assignments so literal `$`
characters survive; unquoted/double-quoted shell assignments expand them. Export
these variables to the backend process (a Compose `.env` file alone does not inject
container environment). A YAML application property containing the hash also needs
safe quoting. Never enable credential-bearing debug dumps or put passwords in URLs.
This feature does not configure TLS: expose it only through verified HTTPS, directly
or through an explicitly managed private reverse proxy. Preserve existing backend
TLS/forwarded-header policy; arbitrary forwarded headers do not establish trust.

### Scrape Config

The following is an operator template, **not** a ready-to-run endpoint or credential.
Replace the DNS name/port and mount private files read-only. The certificate SAN must
match the target name; `ca_file` contains the trusted CA or explicitly trusted public
self-signed certificate. TLS certificate verification must remain enabled.

```yaml
scrape_configs:
  - job_name: 'recordplatform-backend'
    scheme: https
    metrics_path: '/record-platform/actuator/prometheus'
    basic_auth:
      username: 'collector'
      password_file: '/run/secrets/prometheus-scrape-password'
    tls_config:
      ca_file: '/run/secrets/backend-ca.crt'
    static_configs:
      - targets: ['backend.example.internal:443']

  # Storage and FISCO are Dubbo providers without embedded HTTP servers,
  # so they do not expose /actuator/prometheus directly.
  # Collect their metrics via the OTel Collector Prometheus exporter instead.
  - job_name: 'otel-collector'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['otel-collector:8889']
```

### Rotation and Operator Acceptance

Changing the backend username/hash requires a **backend restart**. Publish the new
hash and matching private password file together, restart the backend, then validate
the Prometheus configuration with `promtool check config` and reload Prometheus
(SIGHUP or its explicitly enabled lifecycle reload endpoint). Single-credential
rotation can cause a brief scrape gap; no zero-downtime overlap is promised. Verify
the new password succeeds and the old password is rejected before retiring it.

Run these checks yourself after deployment; the source tests are not live-server
acceptance. `curl --user collector` prompts for the password, keeping it out of
arguments/history. Use the trusted public certificate/CA, never disable verification:

```bash
curl -q --cacert /private/path/backend-ca.crt --user collector --fail \
  https://backend.example.internal/record-platform/actuator/prometheus
curl -q --cacert /private/path/backend-ca.crt --user collector --head \
  https://backend.example.internal/record-platform/actuator/prometheus
curl -q --cacert /private/path/backend-ca.crt --user collector --write-out '%{http_code}\n' \
  https://backend.example.internal/record-platform/actuator/info
```

The first two must return 200 (GET contains actual metrics). Other protected paths
must not return metric/business data with scrape credentials. Repeat the first
request with a wrong/old password: expect 401 and no metrics. With no credentials
and no tenant, the existing outer tenant filter returns 400; with a tenant but no
authentication it returns 401. Confirm the backend target is UP in Prometheus and
fresh business samples arrive; OTel/JVM samples alone do not prove this target.
Existing Bearer admin/monitor and ordinary application login should still work.

References: [Spring Basic authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html),
[Prometheus HTTP authentication and TLS configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/).

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

## Sensitive Upload Logging

Direct-upload session responses contain bearer object-storage URLs. The request
logger skips response caching/body previews for the upload-session route family;
the original response still reaches clients unchanged. Success audits retain
operation metadata but omit sensitive file-operation payloads.

Other log copies use shared field/value redaction for URL aliases, signed query
credentials, nested containers and encoded or incomplete text. Redaction runs
before preview truncation. Inspection is bounded (64 KiB per value, depth 32,
4096 nodes, a shared character budget and four decoding passes); unsafe or
oversized copies are omitted, never emitted as unverified prefixes. Decoded
inspection strings are not logged. Failure audit messages and the corresponding
business/retryable/IO/system and parameter-conversion exception logs are sanitized without changing the
client error or thrown business exception. System errors retain detached sanitized
stack/cause/suppressed diagnostics rather than references to the original Throwable.

Existing logs may already contain capabilities. Restrict access to that historical
evidence and follow incident retention rules; a code update does not erase old
records. Validate a fresh direct upload after deployment: verify unchanged client
responses and no new credential/signature value in text, JSON or audit log copies.

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
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | Explicit transport matching the default gRPC listener |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Collector endpoint |
| `OTEL_TRACES_SAMPLER` | `parentbased_traceidratio` | Sampling strategy |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` | Sampling rate (10%) |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | provider `true`, backend `false` | Bridge application meters for non-HTTP FISCO/storage providers |

The provider bridge is selected per service, not globally when sourcing `env.sh`.
Explicit `true`/`false` overrides are preserved; empty script values use the default.
Backend retains its native authenticated Actuator scrape and is not bridged by default.
Agent 2.26.1 otherwise disables Micrometer instrumentation; see the official
[instrumentation controls](https://opentelemetry.io/docs/zero-code/java/agent/disable/).

The scripts and all three application images explicitly default to `grpc` with
port 4317. Images use `http://otel-collector:4317` on the container network instead
of the scripts' localhost endpoint. Set both values in `.env` for scripts, or pass
both as container environment overrides when switching to HTTP/protobuf:

```bash
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

Use the reachable Collector hostname inside containers. Custom endpoint ports are
preserved verbatim; scripts do not infer transport or rewrite endpoints. Generated
JVM protocol/endpoint options reflect the effective environment values; image
entrypoints do not hardcode conflicting JVM properties. Avoid separately supplying
contradictory system properties, which have higher priority than environment
settings. Java Agent 2.x otherwise defaults to HTTP/protobuf. See the official
[agent configuration](https://opentelemetry.io/docs/zero-code/java/agent/configuration/).

Signal-specific `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` / `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
and `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` / `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` remain
untouched and take priority over generic settings. HTTP signal endpoints must
include `/v1/traces` or `/v1/metrics`; the generic HTTP endpoint is a base URL.
See the official [exporter configuration](https://opentelemetry.io/docs/languages/java/configuration/#properties-exporters).
Validate real application traces in Jaeger and JVM metrics through Prometheus;
healthy monitoring containers or synthetic telemetry alone do not prove application export.

Uses W3C Trace Context propagation for cross-service distributed tracing.

### Jaeger UI

Visit http://localhost:16686 to view trace data.

## Storage Integrity Check

The system periodically verifies active chunk manifests, S3-compatible object metadata, sampled chunk bytes, and blockchain records. Files without an active manifest are reported as a migration condition; the checker never falls back to treating `file.fileHash` as an object content hash.

### How It Works

1. Runs daily at 2:00 AM and samples 1% of successful files.
2. Batch-loads each tenant's active manifests and ordered chunk rows.
3. Applies one randomly selected check level to the sampled run:

| Level | Verification | Object download |
|-------|--------------|-----------------|
| `LIGHTWEIGHT` | Manifest identity/safety contract plus every chunk's path, tenant, size, metadata hash, and declared ETag via `HeadObject` | No |
| `MEDIUM` | Lightweight checks plus chunk order/count/aggregate size and canonical `manifestHash` | No |
| `HEAVY` | Medium checks plus bounded sampled chunk SHA-256 and blockchain record comparison | Selected chunks only |

`file.fileHash` and the v1 manifest `fileHash` compatibility field are chain record identifiers. Object content evidence comes from chunk `plainHash`/`cipherHash`; the ordered manifest proof is `manifestHash`.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `integrity.check.enabled` | `true` | Enable/disable check |
| `integrity.check.schedule.cron` | `0 0 2 * * ?` | Execution schedule |
| `integrity.check.sample-rate` | `0.01` | Sampling rate |
| `integrity.check.batch-size` | `50` | Files per manifest batch; runtime bounds this to `1..1000` |
| `integrity.check.lock-timeout-seconds` | `1800` | Distributed lock lease time |
| `integrity.check.heavy.sample-chunks` | `1` | Unique chunk objects sampled per heavy file check |
| `integrity.check.heavy.max-download-bytes` | `83886080` | Maximum sampled bytes downloaded for one file in one run |

### Admin Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/admin/integrity-alerts` | List integrity alerts |
| `POST /api/v1/admin/integrity-alerts/check` | Trigger manual check |
| `PUT /api/v1/admin/integrity-alerts/{id}/acknowledge` | Acknowledge an alert |
| `PUT /api/v1/admin/integrity-alerts/{id}/resolve` | Resolve an alert |

### Alert Notifications

When integrity anomalies are detected, the system pushes `INTEGRITY_ALERT` events to admins via SSE. Records and events include `alertType`, `severity`, bounded `evidence`, `actualHash`, and `chainHash` where applicable.

New manifest-driven types are `MANIFEST_MISSING`, `MANIFEST_INVALID`, `OBJECT_NOT_FOUND`, `METADATA_MISMATCH`, `CONTENT_HASH_MISMATCH`, and `CHAIN_MISMATCH`; `CHAIN_NOT_FOUND` remains supported. Legacy `HASH_MISMATCH` and `FILE_NOT_FOUND` records remain readable for API compatibility. An unresolved alert with the same tenant, file, and type is not inserted or broadcast again. A distributed lock (Redisson) serializes scheduled and manual checks.

## Production Merkle Batch Trigger

Production admission is disabled until an operator explicitly enables it. When disabled, scheduled execution is not created and a manual trigger returns a disabled result without reading or writing candidates or batches.

| Property | Default | Description |
|----------|---------|-------------|
| `attestation.production.enabled` | `false` | Enable candidate admission and flush |
| `attestation.production.poll-interval-ms` | `30000` | Scheduler interval |
| `attestation.production.initial-delay-ms` | `30000` | Initial scheduler delay |
| `attestation.production.min-batch-size` | `50` | Automatic size threshold |
| `attestation.production.max-batch-size` | `100` | Maximum candidates claimed for one batch |
| `attestation.production.max-wait-seconds` | `600` | Flush the oldest ready candidate after this wait |
| `attestation.production.seed-limit` | `200` | Maximum newly discovered candidates per tenant and run |
| `attestation.production.max-batches-per-run` | `2` | Shared budget for recovered and new batches per tenant and run |
| `attestation.production.claim-lease-seconds` | `120` | Candidate worker lease |
| `attestation.production.candidate-max-attempts` | `3` | Failures before dead letter |

Both endpoints require the admin role and derive the tenant from the authenticated request context; callers cannot supply a cross-tenant ID.

| Endpoint | Description |
|----------|-------------|
| `POST /api/v1/admin/attestation-batches/production/trigger` | Force one bounded run for the current tenant |
| `GET /api/v1/admin/attestation-batches/production/status` | Read feature state, effective limits, candidate backlog, and due batches |

Rollout order: apply Flyway migrations first, deploy with `enabled=false`, inspect status and metrics, then enable one environment at a time. To roll back application behavior, set `enabled=false`; retain the candidate table and leaf evidence columns because deleting them could discard audit state or break already-created batches.

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
| **Attestation P99 Latency** | `otel_blockchain_operation_duration_seconds_bucket` | `histogram_quantile(0.99, sum by (le) (rate(...[window])))` over FISCO observations |
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

> **Note:** Agent 2.26.1's default Micrometer bridge exports timers as histograms in seconds, not client-side quantile series, even if a timer calls `.publishPercentiles()`. Recording rules and dashboard scope `job="otel-collector",exported_job="record-platform-fisco",operation="storeFile"`, apply `rate` before summing by `le`, then estimate quantiles across instances. The existing 5m/30m/1h recording names now represent observations in each interval, not an upper envelope of client summaries. Bucket interpolation is an estimate, not an exact percentile; retain seconds and the 5-second threshold. See [Prometheus histogram functions](https://prometheus.io/docs/prometheus/latest/querying/functions/#histogram_quantile).

### Collection health and no-data behavior

The dashboard shows configured target health and observation counts separately from
business SLOs. `RecordPlatformScrapeTargetDown` covers only configured
`recordplatform-backend` / `otel-collector` targets after 2 minutes. Source-missing
alerts require a successful corresponding scrape for 5 minutes and check the eager
Saga meter, FISCO `otel_blockchain_health`, and JVM meters of the three named services.
Unconfigured optional jobs do not page. Validate required job definitions during
deployment; removing every job cannot be detected from their absent `up` series.

No requests or uploads means undefined ratios, and a never-used timer has no
observations. Missing inventory/telemetry is unknown, never 100% availability or
zero latency. Real successful API traffic with no 5xx series yields 0% errors;
absent HTTP input remains absent. New deployments' 30-day values cover only retained
observations, not a completed 30-day SLO window. Exporter cache presence and a new
scrape timestamp do not prove fresh producer ingestion; verify advancing producer
signals separately. External notification delivery is a separate configuration.

Run `bash tools/ci/check-monitoring.sh` for pinned, network-isolated `promtool check rules`
and numerical `promtool test rules`; Required CI executes the same fail-closed gate.
