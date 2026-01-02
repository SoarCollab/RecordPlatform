# 监控告警

RecordPlatform 的监控、指标和健康检查。

## 健康端点

| 端点 | 说明 |
|------|------|
| `/actuator/health` | 整体健康状态 |
| `/actuator/health/liveness` | Kubernetes 存活探针 |
| `/actuator/health/readiness` | Kubernetes 就绪探针 |
| `/actuator/prometheus` | Prometheus 指标 |
| `/actuator/circuitbreakers` | 熔断器状态 |

## 健康检查组件

`/actuator/health` 端点包含：

| 组件 | 检查内容 |
|------|----------|
| `db` | MySQL 连通性 |
| `redis` | Redis 连通性 |
| `rabbit` | RabbitMQ 连通性 |
| `s3Storage` | S3 节点可用性 |
| `saga` | Saga 事务健康 |
| `outbox` | Outbox 事件健康 |
| `encryption` | 加密策略状态 |

### 响应示例

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

## 关键指标

### Saga 指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `saga_total` | Counter | 按状态统计的 Saga 总数 |
| `saga_duration` | Timer | 执行/补偿耗时 |
| `saga_running` | Gauge | 正在运行的 Saga |
| `saga_pending_compensation` | Gauge | 待补偿的 Saga |

### Outbox 指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `outbox_events_total` | Counter | 按状态统计的事件数 |
| `outbox_publish_latency` | Timer | 事件发布延迟 |
| `outbox_pending` | Gauge | 待发送事件 |
| `outbox_exhausted` | Gauge | 超过最大重试的事件 |

### 存储指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `s3_node_online_status` | Gauge | 节点在线状态 (0/1) |
| `s3_node_load_score` | Gauge | 节点负载评分 |
| `s3_node_operations_total` | Counter | 每节点操作数 |

## 健康阈值

配置告警阈值：

```yaml
# Outbox 阈值
outbox:
  health:
    pending-threshold: 500    # >500 待发送 → DEGRADED
    failed-threshold: 20      # >20 失败 → DOWN

# Saga 阈值
saga:
  health:
    running-threshold: 100    # >100 运行中 → DEGRADED
    failed-threshold: 10      # >10 失败 → DOWN
    pending-compensation-threshold: 50  # >50 待补偿 → DEGRADED
```

## Prometheus 配置

### 抓取配置

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

### 告警规则

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
          summary: "Saga 失败率过高"

      - alert: OutboxBacklog
        expr: outbox_pending > 500
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Outbox 事件积压"

      - alert: S3NodeDown
        expr: s3_node_online_status == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "S3 存储节点离线"
```

## Grafana 仪表盘

### 推荐面板

1. **系统概览**
   - 请求速率和延迟
   - 错误率
   - 活跃连接数

2. **Saga 状态**
   - 运行中 vs 已完成 vs 失败
   - 补偿队列深度
   - 平均耗时

3. **存储健康**
   - 各域节点状态
   - 复制延迟
   - 磁盘使用率

4. **区块链**
   - 交易速率
   - 熔断器状态
   - 重试次数

## SkyWalking 集成

### 配置

部署脚本自动配置 SkyWalking Agent：

```bash
SKYWALKING_OPTS="-javaagent:/path/to/skywalking-agent.jar \
  -Dskywalking.agent.service_name=platform-backend \
  -Dskywalking.collector.backend_service=skywalking-oap:11800"
```

### 分布式追踪

SkyWalking 提供：
- 跨服务请求追踪
- 慢查询检测
- 服务依赖映射
- 错误追踪

## 日志聚合

### ELK Stack 配置

```yaml
# Logstash 管道
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

