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
| `s3_node_online_status` | Gauge | Node online status (0/1) |
| `s3_node_load_score` | Gauge | Node load score |
| `s3_node_operations_total` | Counter | Operations per node |

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

  - job_name: 'recordplatform-storage'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['storage:8092']

  - job_name: 'recordplatform-fisco'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['fisco:8091']
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

