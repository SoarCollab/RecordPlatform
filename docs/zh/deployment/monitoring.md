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
| `s3_node_online_status` | Gauge | 节点在线状态 (0/1)，由 backend actuator 基于存储容量快照桥接暴露 |
| `s3_node_usage_percent` | Gauge | 节点磁盘使用率 (0-100)，由 backend actuator 基于存储容量快照桥接暴露 |

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

## 分布式追踪 (OpenTelemetry)

项目已集成 OpenTelemetry Java Agent v2.26.1，三个 Java 服务自动采集 traces 和 metrics。

### 基础设施

| 组件 | 端口 | 说明 |
|------|------|------|
| OTel Collector | 4317 (gRPC), 4318 (HTTP), 8889 (Prometheus) | 数据收集与转发 |
| Jaeger | 16686 | 追踪可视化 UI |

### 启用方式

**Docker 部署**：设置 `OTEL_JAVAAGENT_ENABLED=true`（默认启用）

**本地开发**：

```bash
./scripts/start.sh start --otel all
```

> `--otel` 与 `--skywalking` 互斥，不能同时启用。

### 配置

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Collector 地址 |
| `OTEL_TRACES_SAMPLER` | `parentbased_traceidratio` | 采样策略 |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` | 采样率 (10%) |

W3C Trace Context 标准传播，支持跨服务链路追踪。

### Jaeger UI

访问 http://localhost:16686 查看追踪数据。

## 存储完整性校验

系统定期验证 S3 存储文件与区块链记录的一致性。

### 工作原理

1. 每天凌晨 2 点自动执行，采样 1% 的文件
2. 验证文件在 S3 中的存在性
3. 比对数据库哈希与链上哈希的一致性
4. 发现异常时创建告警并通过 SSE 通知管理员

> 由于文件存储层采用加密，校验不会重新计算文件内容哈希，而是验证 S3 文件存在性 + DB 与链上哈希的一致性。

### 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `integrity.check.enabled` | `true` | 是否启用 |
| `integrity.check.cron` | `0 0 2 * * ?` | 执行时间 |
| `integrity.check.sample-rate` | `0.01` | 采样率 |
| `integrity.check.batch-size` | `50` | 批次大小 |

### 管理接口

| 端点 | 说明 |
|------|------|
| `GET /api/v1/admin/integrity-alerts` | 查询告警列表 |
| `POST /api/v1/admin/integrity-alerts/check` | 手动触发校验 |
| `PUT /api/v1/admin/integrity-alerts/{id}/acknowledge` | 确认告警 |
| `PUT /api/v1/admin/integrity-alerts/{id}/resolve` | 解决告警 |

### 告警通知

发现完整性异常时，系统通过 SSE 推送 `INTEGRITY_ALERT` 事件通知管理员，告警记录存储在 `integrity_alert` 表中。分布式锁（Redisson）确保不会并发执行多个校验任务。

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

## SLO/SLI 可观测性

### 服务级别指标（SLI）

| SLI | 指标来源 | 计算方式 |
|-----|---------|---------|
| **上传成功率** | `saga_total_total{status}` | completed / (completed + failed + compensated) |
| **存证 P99 延迟** | `blockchain_operation_duration_seconds{quantile="0.99"}` | 基于导出的 P99 样本做 `max_over_time(...[window])` 窗口汇总 |
| **存储可用性** | `s3_node_online_status` | 当前配置节点中的在线节点数 / 当前配置节点总数 |
| **API 错误率** | `http_server_requests_seconds_count{status}` | 5xx 数量 / 总请求数 |

### 服务级别目标（SLO）

| SLO | 目标 | 窗口 | 错误预算（30 天） |
|-----|------|------|-----------------|
| 上传成功率 | >= 99.5% | 30 天滚动 | 0.5%（约 216 分钟） |
| 存证 P99 延迟 | <= 5s | 30 天滚动 | — |
| 存储可用性 | >= 99.9% | 30 天滚动 | 0.1%（约 43 分钟） |
| API 错误率 | <= 0.5% | 30 天滚动 | 0.5%（约 216 分钟） |

### 燃尽率告警

采用 Google SRE 多窗口燃尽率模型。短窗口和长窗口必须同时触发，以减少误报。

| 严重性 | 短窗口 | 长窗口 | 燃尽率 | 响应 |
|--------|--------|--------|--------|------|
| **Critical** | 5 分钟 | 1 小时 | 14.4x | 立即处理 |
| **Warning** | 30 分钟 | 6 小时 | 6x | 当日处理 |
| **Info** | 1 小时 | 1 天 | 3x | 下周处理 |

### 配置文件

| 文件 | 用途 |
|------|------|
| `config/prometheus/recording-rules.yml` | SLI 预计算规则（多时间窗口） |
| `config/prometheus/alerting-rules.yml` | 燃尽率告警 + 错误预算耗尽告警 |
| `config/grafana/slo-dashboard.json` | Grafana v10+ SLO 概览仪表盘 |

在 Prometheus 中加载：

```yaml
rule_files:
  - "config/prometheus/recording-rules.yml"
  - "config/prometheus/alerting-rules.yml"
```

### Grafana 仪表盘

将 `config/grafana/slo-dashboard.json` 导入 Grafana。仪表盘包含：

| 面板行 | 内容 |
|--------|------|
| SLO 总览 | 4 个 Stat 面板，显示当前 SLI 值与目标对比 |
| 错误预算 | 上传和 API 错误预算剩余仪表 |
| 上传成功率 | 时序图，含 99.5% SLO 阈值线 |
| 存证延迟 | P50/P95/P99 时序图，含 5s 阈值线 |
| 存储可用性 | 可用性比率 + 节点状态表格 |
| API 错误率 | 错误率时序图 + Top-5 错误端点 |
| Resilience4j | 断路器状态 + 重试次数 |

> **注意**：存证延迟使用 Micrometer 预计算的客户端分位数（`.publishPercentiles()`），因此当前 SLO 规则用 `max_over_time(...)` 对导出的 P99 样本做窗口汇总，而不是 `histogram_quantile(...)`。这些分位数不可跨多实例聚合。如需多实例部署，请在 `FiscoMetrics.java` 的 Timer builder 中添加 `.publishPercentileHistogram(true)`。
