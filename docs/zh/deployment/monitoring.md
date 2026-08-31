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
| `rabbitmq` | 已配置应用队列的被动探测 |
| `s3Storage` | S3 节点可用性 |
| `saga` | Saga 事务健康 |
| `outbox` | Outbox 事件健康 |
| `encryption` | 加密策略状态 |

应用队列探针默认配置为 `spring.rabbitmq.health.queue=file.stored.queue`。
`file.stored` 是路由键，不是队列名；被动探测不能创建队列或消费消息。
已配置队列不可用时返回 `DEGRADED`。

Outbox 与 Saga 健康检查在 Actuator 的 `getHealth` 入口聚合所有租户的计数，
不会假定租户为 `0`、改变普通查询的租户隔离，或在检查后遗留跨租户上下文。
默认 `show-details: never` 继续隐藏匿名健康响应中的这些计数。

整体状态优先级为 `DOWN,OUT_OF_SERVICE,DEGRADED,UP,UNKNOWN`，不能把降级组件汇总成 `UP`。
HTTP 兼容性保持不变：`DOWN` 与 `OUT_OF_SERVICE` 返回 503，`DEGRADED` 返回 200。
调用方必须检查 JSON 状态，不能只看 HTTP 状态码；`scripts/start.sh` 只接受顶层 `UP`。
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

生产 `SagaMetrics` 中名为 `saga.total` 的 counter，经固定版本 Prometheus registry
实际导出为 `saga_total`，标签为 `status=started|completed|failed|compensated`，四种
状态在请求流量到来前即注册。Exporter 已规范化 counter 后缀，不应再追加第二个
`_total`。可执行 exporter 契约测试会对照生产注册结果检查两份规则和数值 fixture。

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

### 生产存证批次指标

所有标签都是固定枚举；指标刻意不包含 tenant、file、candidate 或 batch ID，以限制时序基数。

| 指标 | 类型 | 标签 / 说明 |
|------|------|-------------|
| `app_attestation_candidate_total` | Counter | `result=admitted\|batched\|dead_letter` |
| `app_attestation_candidate_backlog` | Gauge | 最近一次定时任务观察到的全局值，`status=ready\|dead_letter` |
| `app_attestation_batch_total` | Counter | `status=completed\|retry\|manual_review` |
| `app_attestation_batch_size` | Distribution summary | 每个已创建 batch 的 manifest 证据叶子数 |
| `app_attestation_batch_latency_seconds` | Timer | candidate 准入到本地 batch 创建的耗时 |
| `app_attestation_production_run_total` | Counter | `result=completed\|disabled\|failed` |

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

### 专用采集身份

后端指标**不允许匿名读取**。机器采集身份默认关闭；已有 admin/monitor Bearer
访问仍需匹配的业务租户。通过以下后端配置显式启用机器采集：

| 环境变量 | 应用属性 | 默认值 |
|---|---|---|
| `PROMETHEUS_SCRAPE_ENABLED` | `security.prometheus-scrape.enabled` | `false` |
| `PROMETHEUS_SCRAPE_USERNAME` | `security.prometheus-scrape.username` | 空 |
| `PROMETHEUS_SCRAPE_PASSWORD_HASH` | `security.prometheus-scrape.password-hash` | 空 |

使用独立用户名（1–64 位 ASCII 字母/数字、点、下划线或连字符，首位必须是字母或
数字）及 BCrypt 哈希（`$2a$`、`$2b$` 或 `$2y$`）。启用后缺失或非法配置会使
启动失败，拒绝明文或 `{noop}` 密码。仅配置的 servlet context 下精确的
`GET`/`HEAD /actuator/prometheus` 接受此 Basic 身份。它只有
`PROMETHEUS_SCRAPE` 权限，**不是**业务 admin/monitor，不得访问文件、审计/日志、
其他 actuator、子路径或写方法。机器路径忽略调用者租户头，不建立业务租户上下文；
不要给采集器编造 `X-Tenant-ID`。

在本地密码管理器生成强随机密码，保存至 Prometheus 私有 `password_file`
（仅所有者可读写 `0600`，只包含密码且无末尾换行）。使用可信工具交互生成
BCrypt，例如 `htpasswd -cB -C 12 /private/path/scrape.htpasswd collector`，在提示中
输入同一密码，不要使用 `-b` 将密码放入命令行。后端私有配置只保存哈希，不含
`username:` 前缀。哈希文件同样限制权限；真实用户名、哈希、密码文件和证书均不提交。

shell/dotenv 中用单引号包裹完整哈希以保留 `$`；shell 未引用或双引号赋值会展开
这些字符。变量必须实际导出给后端进程（Compose 的 `.env` 本身不会自动注入容器
环境）；直接写应用 YAML 的哈希也需正确引用。禁止凭据调试转储或 URL 携带密码。
此功能不配置 TLS：仅通过证书验证的 HTTPS 暴露，可由明确管理的内网反向代理终止
TLS。保留已有后端 TLS/转发头策略，不能信任任意调用者提供的转发头。

### 抓取配置

以下是需要替换的运维模板，不是可直接使用的地址或凭据。修改 DNS/端口并只读挂载
私有文件；证书 SAN 必须匹配目标名称。`ca_file` 存放受信 CA 或显式信任的自签
公钥证书，必须保持 TLS 证书校验。

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

  # storage 和 fisco 是 Dubbo Provider，没有内嵌 HTTP 服务器，
  # 不直接暴露 /actuator/prometheus。通过 OTel Collector 的 Prometheus 导出端点采集。
  - job_name: 'otel-collector'
    metrics_path: '/metrics'
    static_configs:
      - targets: ['otel-collector:8889']
```

### 轮换与用户验收

修改后端用户名/哈希需要**重启后端**。同时发布新哈希与匹配的私有密码文件，重启
后端，再执行 `promtool check config` 并重载 Prometheus（SIGHUP 或显式开启的
lifecycle reload 接口）。单凭据轮换可能短暂中断采集，不承诺零停机重叠。确认新
密码成功、旧密码拒绝后再停用旧凭据。

部署后由用户执行以下检查；源码测试不是服务器验收。`curl --user collector`
会提示输入密码，避免出现在参数或历史中；使用受信 CA/公钥证书，不能跳过校验：

```bash
curl -q --cacert /private/path/backend-ca.crt --user collector --fail \
  https://backend.example.internal/record-platform/actuator/prometheus
curl -q --cacert /private/path/backend-ca.crt --user collector --head \
  https://backend.example.internal/record-platform/actuator/prometheus
curl -q --cacert /private/path/backend-ca.crt --user collector --write-out '%{http_code}\n' \
  https://backend.example.internal/record-platform/actuator/info
```

前两项应返回 200（GET 含真实指标）。采集凭据访问其他受保护路径不得返回指标或
业务数据。使用错误/旧密码重复第一项应返回 401 且无指标。无凭据又无租户时，
既有外层租户过滤器返回 400；有租户但无认证时返回 401。确认 Prometheus 的后端
target 为 UP 且出现新的业务指标；仅有 OTel/JVM 数据不代表后端 target 正常。
已有 Bearer admin/monitor 访问和普通业务登录也应继续正常。

参考：[Spring Basic authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)、
[Prometheus HTTP authentication and TLS configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)。

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

## 敏感上传日志

直接上传会话响应包含对象存储 bearer URL。请求日志过滤器对 upload-session 路由族
跳过响应缓存和正文预览，客户端仍收到原始响应字节。成功审计保留操作元信息，
但省略敏感文件操作的请求/响应载荷。

其他日志副本统一对 URL 别名、签名查询凭据、嵌套容器及编码/不完整文本脱敏，
并在截断预览前完成检查。检查受每值 64 KiB、32 层、4096 节点、共享字符预算及
四轮解码限制；不能安全检查或过大的副本会省略，不输出未经验证的前缀，检测用
解码字符串也不写入日志。失败审计消息及对应业务、重试、IO、系统和参数转换异常日志会脱敏，
但不修改客户端错误或实际抛出的业务异常；系统异常保留分离且脱敏的堆栈、cause
和 suppressed 诊断信息，不把原始 Throwable 交给日志组件。

历史日志可能已包含能力凭据。应限制历史证据访问并遵循事件保留规则；更新代码
不会自动清除旧记录。部署后用一次新直接上传验收：客户端响应正常，text、JSON
和审计日志副本均没有新增明文 credential/signature 值。

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
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | 与默认 gRPC 监听端口匹配的显式传输协议 |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Collector 地址 |
| `OTEL_TRACES_SAMPLER` | `parentbased_traceidratio` | 采样策略 |
| `OTEL_TRACES_SAMPLER_ARG` | `0.1` | 采样率 (10%) |
| `OTEL_INSTRUMENTATION_MICROMETER_ENABLED` | provider `true`，backend `false` | 非 HTTP 的 FISCO/storage 服务通过 bridge 导出业务指标 |

脚本在每次生成服务参数时选择 bridge 默认值，不会在 source `env.sh` 时全局开启。
显式 `true`/`false` 原样保留，脚本中的空值使用默认值。后端默认仍使用原生、经过认证的
Actuator scrape。Agent 2.26.1 自身默认关闭 Micrometer instrumentation，参见官方
[instrumentation 配置](https://opentelemetry.io/docs/zero-code/java/agent/disable/)。

脚本和三个应用镜像均显式默认使用 `grpc` 与 4317 端口。镜像使用容器网络中的
`http://otel-collector:4317`，不是脚本的 localhost。切换 HTTP/protobuf 时，
脚本在 `.env` 中同时设置下面两项，容器则同时传入对应环境变量：

```bash
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
```

容器内应使用实际可达的 Collector 主机名。自定义端口原样保留，脚本不会根据端口
猜测协议或改写端点。生成的 JVM 协议和端点参数来自最终环境值；镜像入口不写死
与环境变量冲突的 JVM 参数。不要另行配置相互矛盾的 system properties，其优先级
高于环境变量。Java Agent 2.x 自身默认使用 HTTP/protobuf，参见官方
[Agent 配置](https://opentelemetry.io/docs/zero-code/java/agent/configuration/)。

单信号覆盖 `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` / `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`
以及 `OTEL_EXPORTER_OTLP_METRICS_PROTOCOL` / `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`
保持不变且优先于通用项。HTTP 单信号端点必须带 `/v1/traces` 或 `/v1/metrics`，
通用 HTTP 端点则是基础 URL，参见官方
[Exporter 配置](https://opentelemetry.io/docs/languages/java/configuration/#properties-exporters)。
验收必须在 Jaeger 中看到真实应用 trace，并通过 Prometheus 查询 JVM metrics；
监测容器健康或合成遥测成功不能替代应用遥测验收。

W3C Trace Context 标准传播，支持跨服务链路追踪。

### Jaeger UI

访问 http://localhost:16686 查看追踪数据。

## 存储完整性校验

系统定期验证 active chunk manifest、S3 兼容对象 metadata、抽样分片内容和区块链记录。缺少 active manifest 的文件会被标记为迁移状态；巡检不会回退到把 `file.fileHash` 当作对象内容哈希。

### 工作原理

1. 每天凌晨 2 点自动执行，采样 1% 的成功文件。
2. 按租户批量加载 active manifest 及其有序分片。
3. 对本轮样本应用随机选择的巡检级别：

| 级别 | 校验内容 | 对象下载 |
|------|----------|----------|
| `LIGHTWEIGHT` | manifest 身份/安全合同，以及所有分片的路径、tenant、大小、metadata hash 和已声明 ETag | 不下载 |
| `MEDIUM` | 轻量校验，加分片顺序/数量/聚合大小和 canonical `manifestHash` | 不下载 |
| `HEAVY` | 中量校验，加受字节上限约束的分片抽样 SHA-256 和链记录比对 | 仅选中的分片 |

`file.fileHash` 和 manifest v1 的兼容字段 `fileHash` 是链记录 ID。对象内容证据来自分片 `plainHash`/`cipherHash`，有序 manifest 的证明是 `manifestHash`。

### 配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `integrity.check.enabled` | `true` | 是否启用 |
| `integrity.check.schedule.cron` | `0 0 2 * * ?` | 执行时间 |
| `integrity.check.sample-rate` | `0.01` | 采样率 |
| `integrity.check.batch-size` | `50` | 每批文件数；运行时限制在 `1..1000` |
| `integrity.check.lock-timeout-seconds` | `1800` | 分布式锁租期 |
| `integrity.check.heavy.sample-chunks` | `1` | 每个文件在重型巡检中抽样的唯一分片数 |
| `integrity.check.heavy.max-download-bytes` | `83886080` | 单文件单轮允许下载的最大抽样字节数 |

### 管理接口

| 端点 | 说明 |
|------|------|
| `GET /api/v1/admin/integrity-alerts` | 查询告警列表 |
| `POST /api/v1/admin/integrity-alerts/check` | 手动触发校验 |
| `PUT /api/v1/admin/integrity-alerts/{id}/acknowledge` | 确认告警 |
| `PUT /api/v1/admin/integrity-alerts/{id}/resolve` | 解决告警 |

### 告警通知

发现完整性异常时，系统通过 SSE 推送 `INTEGRITY_ALERT` 事件通知管理员。记录和事件包含 `alertType`、`severity`、有界 `evidence`，以及适用时的 `actualHash`、`chainHash`。

Manifest 驱动的新类型包括 `MANIFEST_MISSING`、`MANIFEST_INVALID`、`OBJECT_NOT_FOUND`、`METADATA_MISMATCH`、`CONTENT_HASH_MISMATCH`、`CHAIN_MISMATCH`，并继续支持 `CHAIN_NOT_FOUND`。历史 `HASH_MISMATCH`、`FILE_NOT_FOUND` 记录仍可读取以保持 API 兼容。同一 tenant、文件和类型存在未解决告警时，不重复写入或推送。分布式锁（Redisson）串行化定时和手工巡检。

## 生产 Merkle Batch 触发器

生产准入默认关闭，必须由运维显式启用。关闭时不会创建定时任务；人工触发返回 disabled 结果，且不读取或写入 candidate、batch、leaf。

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `attestation.production.enabled` | `false` | 启用 candidate 准入和 flush |
| `attestation.production.poll-interval-ms` | `30000` | 调度间隔 |
| `attestation.production.initial-delay-ms` | `30000` | 调度首次延迟 |
| `attestation.production.min-batch-size` | `50` | 自动 flush 数量阈值 |
| `attestation.production.max-batch-size` | `100` | 单个 batch 最多领取的 candidate 数 |
| `attestation.production.max-wait-seconds` | `600` | 最老 READY candidate 的最大等待时间 |
| `attestation.production.seed-limit` | `200` | 每租户每轮最多发现的新 candidate 数 |
| `attestation.production.max-batches-per-run` | `2` | 每租户每轮恢复与新建 batch 的共享预算 |
| `attestation.production.claim-lease-seconds` | `120` | candidate worker 租约 |
| `attestation.production.candidate-max-attempts` | `3` | 进入 dead letter 前的最大失败次数 |

两个接口都要求管理员角色，并从认证请求上下文取得租户；调用方不能提交跨租户 ID。

| 端点 | 说明 |
|------|------|
| `POST /api/v1/admin/attestation-batches/production/trigger` | 为当前租户强制执行一轮有界处理 |
| `GET /api/v1/admin/attestation-batches/production/status` | 查询 feature 状态、有效上限、candidate backlog 和 due batch |

灰度顺序：先应用 Flyway migration，以 `enabled=false` 部署，检查状态和指标，再逐环境启用。回滚应用行为时设置 `enabled=false`；必须保留 candidate 表和 leaf 证据列，删除它们会丢失审计状态或破坏已创建 batch。

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
| **上传成功率** | `saga_total{status}` | completed / (completed + failed + compensated) |
| **存证 P99 延迟** | `otel_blockchain_operation_duration_seconds_bucket` | 对 FISCO 观察值计算 `histogram_quantile(0.99, sum by (le) (rate(...[window])))` |
| **存储可用性** | `s3_node_online_status` | 对按 `(node, fault_domain)` 去重后的瞬时在线节点占比做 30 天滚动平均 |
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
| 存储可用性 | 30 天滚动可用性比率 + 节点状态表格 |
| API 错误率 | 错误率时序图 + Top-5 错误端点 |
| Resilience4j | 断路器状态 + 重试次数 |

> **注意**：Agent 2.26.1 默认 Micrometer bridge 将 Timer 导出为秒单位的直方图，而非客户端 quantile 序列，即使 Timer 调用了 `.publishPercentiles()`。规则和面板限定 `job="otel-collector",exported_job="record-platform-fisco",operation="storeFile"`，先对每条 counter 计算 `rate`，再按 `le` 求和并跨实例估算分位数。现有 5m/30m/1h recording 名称代表对应区间的观察值，不再是客户端分位数上包络。桶内插值是估算而非精确分位数；保留秒单位和 5 秒阈值。参见 [Prometheus 直方图函数](https://prometheus.io/docs/prometheus/latest/querying/functions/#histogram_quantile)。

FISCO Timer 必须提供显式 `serviceLevelObjectives(Duration...)` 桶边界，包含5秒 SLO
阈值。固定版本 bridge 中，仅配置客户端 percentiles 或
`publishPercentileHistogram(true)` 不会提供可用的有限桶建议。真实操作后，除
`_count`/`_sum` 外还必须确认有限 `le` 桶；只有 `+Inf` 时即使计数非零也无法计算分位数。
验收 `rate` 需在首次导出基线之后再观察一次累计计数增长。

### 采集健康与无数据语义

面板独立展示已配置 target 的健康和观察次数。`RecordPlatformScrapeTargetDown` 只对
已配置的 `recordplatform-backend` / `otel-collector` 连续失败 2 分钟告警。
源指标缺失告警要求对应 scrape 成功持续 5 分钟，并检查启动即注册的 Saga meter、
FISCO `otel_blockchain_health` 和三个明确服务的 JVM meters。未配置的可选 job 不告警；
部署时必须校验必需 job 定义，删除全部 job 无法仅靠不存在的 `up` 序列检测。

无请求或上传时比率未定义，从未调用的 Timer 没有观察值。缺失库存/遥测是未知，不能
填成 100% 可用或零延迟。有成功 API 请求但从未发生 5xx 时错误率为 0%；所有 HTTP
输入缺失时保持缺失。新部署的 30 天指标仅覆盖实际保留的观察数据，不代表完成了
30 天 SLO 窗口。Exporter 缓存存在以及新的 scrape 时间戳不证明生产者刚刚发送数据，
仍需独立验证生产者信号持续推进。外部告警通知投递属于独立配置。

执行 `bash tools/ci/check-monitoring.sh`，使用固定镜像、无网络的 `promtool check rules`
和数值 `promtool test rules`；Required CI 强制执行同一检查，不以缺少工具为成功。
