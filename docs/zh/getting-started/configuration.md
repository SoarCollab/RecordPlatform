# 配置说明

本指南介绍 RecordPlatform 的环境变量和配置选项。

::: tip 配置迁移说明
自 v2.0 起，敏感配置（数据库凭据、Redis、邮件 SMTP、RabbitMQ）已迁移至 Nacos 配置中心。环境变量中仅保留 Nacos 连接信息和安全密钥（JWT_KEY）。完整的配置结构请参阅 [Nacos 配置模板](/nacos-config-template.yaml)。
:::

## 环境变量

复制示例文件并自定义：

```bash
cp .env.example .env
vim .env
```

### 核心配置

| 分类 | 变量 | 说明 | 默认值 |
|------|------|------|--------|
| **Nacos** | `NACOS_HOST` | Nacos 服务器 | localhost |
| | `NACOS_PORT` | Nacos 端口 | 8848 |
| | `NACOS_USERNAME` | Nacos 用户名 | 必填，无默认值 |
| | `NACOS_PASSWORD` | Nacos 密码 | 必填，无默认值 |
| **Profile** | `SPRING_PROFILES_ACTIVE` | Spring Profile | local |

### 安全配置

| 变量 | 说明 | 要求 |
|------|------|------|
| `JWT_KEY` | JWT 签名密钥 + ID 加密派生 | 至少 32 字符，高熵值 |
| `PUBLIC_REGISTRATION_TENANT_ID` | 公开注册使用的服务端租户 | 显式配置；请求头不决定注册租户 |
| `RATE_LIMIT_TRUSTED_PROXY_CIDRS` | 公共 proof 客户端 IP 限流使用的可信代理数字 IP/CIDR | 默认空；只配置平台控制的代理 |
| `BLOCKCHAIN_RPC_TOKEN` | 后端调用 FISCO Dubbo 服务的共享令牌 | backend 与 fisco 两端必填且一致，无默认值 |
| `RECORD_PLATFORM_UID_SALT` | UID 混淆用盐值 | 建议 8–16 字符随机字符串 |
| `RECORD_PLATFORM_CLIENT_KEY` | UID 混淆用客户端密钥 | 建议 16–32 字符随机字符串 |

`RATE_LIMIT_TRUSTED_PROXY_CIDRS` 最多接受 64 个逗号分隔的数字 IPv4/IPv6 地址或 CIDR，配置总长最多 4096 字符。直连部署以及代理拓扑尚未核验的部署必须保持为空；此时后端忽略 `X-Forwarded-For`、`X-Real-IP` 和 `Forwarded`，只使用规范化的直接 socket peer。反向代理后的空 allowlist 会把所有调用者安全地放入代理 peer 的同一个 120/60 桶，因而可能更早拒绝请求。hostname、URL、端口、zone ID、空项、非法前缀、`0.0.0.0/0` 和 `::/0` 会让应用启动失败。该配置是启动时固定的信任边界，修改后必须重启。

只有立即 socket peer 命中 allowlist 时，后端才从右向左解析一条最多 1024 字符、16 hops 的 XFF header 并跳过可信 hop；仅在 XFF 缺失时考虑 `X-Real-IP`。重复、非法、超长或超 hop header 都回退立即 peer。每个受控代理都必须覆盖调用者原始转发头，或安全追加其从 socket 得到的上一跳。Spring/container 转发头重写固定为 `server.forward-headers-strategy=none`；配置 `server.tomcat.remoteip.remote-ip-header` 或 `server.tomcat.remoteip.protocol-header` 会启动失败。不得再安装 `ForwardedHeaderFilter`、外部 Tomcat `RemoteIpValve` 或等价的第二套解析器。

### 存储配置

S3 兼容存储通过 Nacos 配置。基本环境变量：

| 变量 | 说明 |
|------|------|
| `S3_ENDPOINT` | S3 端点 URL |
| `S3_ACCESS_KEY` | 访问密钥 |
| `S3_SECRET_KEY` | 私有密钥 |
| `S3_BUCKET_NAME` | Bucket 名称 |

故障域配置通过 Nacos 管理，支持运行时刷新。

### 区块链配置

| 变量 | 说明 | 示例 |
|------|------|------|
| `BLOCKCHAIN_ACTIVE` | 激活的链类型 | `local-fisco`, `bsn-fisco`, `bsn-besu` |
| `FISCO_PEER_ADDRESS` | FISCO 节点地址 | `127.0.0.1:20200` |
| `FISCO_CHAIN_ID` | 预期的本地 FISCO chain ID | `chain0` |
| `FISCO_GROUP_ID` | 预期的本地 FISCO group ID | `group0` |
| `BSN_FISCO_CHAIN_ID` | 预期的 BSN FISCO chain ID；`BLOCKCHAIN_ACTIVE=bsn-fisco` 时无默认值且必填 | 服务商分配值 |
| `BSN_BESU_RPC_URL` | BSN Besu JSON-RPC 地址；`BLOCKCHAIN_ACTIVE=bsn-besu` 时必填 | 服务商分配的 HTTPS URL |
| `BSN_BESU_CHAIN_ID` | 预期的 BSN Besu 数字 chain ID | 服务商分配值 |
| `BSN_BESU_PRIVATE_KEY` | 本地 Besu signer 私钥 | 仅由部署密钥注入，禁止提交值 |
| `BSN_BESU_CONTRACT_STORAGE` | 已核验的 BSN Besu Storage 合约地址 | `0x...` |
| `BSN_BESU_CONTRACT_SHARING` | 已核验的 BSN Besu Sharing 合约地址 | `0x...` |
| `BSN_BESU_NONCE_STATE_DIRECTORY` | 持久 nonce journal 与 signer 所有权锁目录 | `/var/lib/record-platform/besu-nonce` |
| `FISCO_STORAGE_CONTRACT` | Storage 合约地址 | `0x...` |
| `FISCO_SHARING_CONTRACT` | Sharing 合约地址 | `0x...` |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_TX` | 必填部署交易哈希；活动链回执必须存在并匹配配置地址/区块 | `0x` + 64 位 hex |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_BLOCK` | 必填部署区块号，必须来自同一成功回执 | 非负十进制整数 |
| `FISCO_{STORAGE,SHARING}_DEPLOYMENT_EFFECTIVE_AT` | 必填实际激活时间，与交易/区块一起配置 | UTC `YYYY-MM-DDTHH:MM:SSZ` |
| `CONTRACT_DEPLOYMENT_RECEIPT_DIR` | 保存公开部署审计回执的持久化受限目录 | 本地开发使用 `log/contract-deployments` |

`scripts/contract-deploy.sh` 要求显式配置本地 chain/group，并在编译及每笔部署前与 Console `getGroupInfo` 完全对账。脚本在同一个 Console 会话中查询每笔交易回执和 `getGroupInfo`，要求 FISCO 显式成功状态 `0`，最终 transaction/address/block 全部取自该同一回执。两个合约的三项部署证据均为必填；legacy 整组空值现在会阻止服务启动。经审查的 BSN 部署沿用这些变量名，启动时由所选 BSN FISCO 或 Besu 客户端重新验证（Besu 必须显式为状态 `1`）。门禁脚本用同一个生效时间原子写回两个三元组，并先发布不含凭据的结构化回执；生产环境应把回执目录放在非临时应用存储之外。`env-check.sh` 只校验格式，最终必须重启 `platform-fisco` 执行活动链回执、runtime code 和身份核验。

BSN Besu 原始交易按规范化 signer，以节点 `PENDING` nonce 和本地持久高水位共同保留 nonce。`BSN_BESU_NONCE_STATE_DIRECTORY` 没有运行时默认值：目录必须支持可靠的 Java/POSIX 文件锁和原子替换，能够跨进程/容器重启保留，并由所有可能使用同一 `(chainId, signer)` 的受支持进程共享。服务在整个 JVM 生命周期持有 signer 独占锁，因此共享该目录的第二个 writer 会启动失败。配置的 signer key 只能由该 coordinator 使用，禁止外部钱包或未协调进程复用。禁止把目录放在容器临时层、通过删除状态文件处理事故，或让同一 signer 在独立主机上 active-active。冷备接管前必须先从外部 fence 旧 writer，并复用同一锁/状态卷。

### SSL 配置（生产环境）

| 变量 | 说明 |
|------|------|
| `SSL_KEY_STORE` | 密钥库路径 |
| `SSL_KEY_STORE_PASSWORD` | 密钥库密码 |
| `REQUIRE_SSL` | 强制 HTTPS (true/false) |
| `HTTP_REDIRECT_PORT` | HTTP 重定向端口 |

### 服务端口配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVER_PORT` | 后端 REST API 端口 | 8000（本地/开发推荐；prod profile 未设置时默认 8080） |
| `DUBBO_FISCO_PORT` | FISCO Dubbo 服务端口 | 8091 |
| `DUBBO_STORAGE_PORT` | Storage Dubbo 服务端口 | 8092 |
| `DUBBO_HOST` | 服务注册 IP（用于 Docker 环境） | Provider 服务必填；运行时无默认值 |
| `QOS_BACKEND_PORT` | Backend QoS 管理端口 | 22330 |
| `QOS_FISCO_PORT` | FISCO QoS 管理端口 | 22331 |
| `QOS_STORAGE_PORT` | Storage QoS 管理端口 | 22332 |

> **注意**: `DUBBO_HOST` 在 Docker 环境中非常重要，确保服务注册使用可访问的 IP 而非 Docker 网桥 IP。请在 `.env` 或部署密钥中显式设置。

### 日志配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LOG_LEVEL` | 应用日志级别 | INFO |
| `LOG_PATH` | 日志文件输出目录 | /var/log/record-platform |

### CORS 配置

| 变量 | 说明 | 示例 |
|------|------|------|
| `CORS_ALLOWED_ORIGINS` | 允许的前端域名（逗号分隔） | `http://localhost:3000,http://localhost:5173` |

### API 文档配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `KNIFE4J_USERNAME` | Knife4j/Swagger UI 用户名 | 必填，无默认值 |
| `KNIFE4J_PASSWORD` | Knife4j/Swagger UI 密码 | 必填，无默认值 |

### APM 配置（可选）

SkyWalking 分布式追踪集成：

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SW_AGENT_COLLECTOR_BACKEND_SERVICES` | SkyWalking OAP 收集器 | localhost:11800 |
| `SW_AGENT_NAME` | SkyWalking 中的服务名 | record-platform |
| `SW_JDBC_TRACE_SQL_PARAMETERS` | 追踪 SQL 参数 | true |

## Profile 配置

可用 Profile: `local`, `dev`, `prod`

```bash
# 使用指定 Profile 运行
java -jar app.jar --spring.profiles.active=prod
```

### Profile 差异

| 特性 | local | dev | prod |
|------|-------|-----|------|
| Swagger UI | 启用 | 启用 | 禁用 |
| Druid 监控 | 启用 | 启用 | 禁用 |
| Debug 日志 | 启用 | 部分 | 禁用 |
| 强制 SSL | 否 | 否 | 是 |

## Nacos 配置

动态配置通过 Nacos 管理。模板：`docs/public/nacos-config-template.yaml`

### 关键 Nacos 配置

```yaml
# 存储节点与故障域配置
storage:
  # 必须配置：活跃域列表
  active-domains:
    - domain-a
    - domain-b

  # 可选：外部访问端点（v3.2.0 新增）
  # 用于生成预签名 URL 时替换内部端点地址，解决跨网段（如 VPN）访问问题
  # 格式：https://host[:port]（不带尾部斜杠）
  external-endpoint: https://s3-secondary.example.com

  # 可选：备用域（用于故障转移）
  standby-domain: standby

  # 副本策略配置（v3.1.0 新增）
  replication:
    factor: 2                     # 副本数量，默认=活跃域数量
    quorum: auto                  # 仲裁策略: auto|majority|all|具体数字

  # 降级写入配置（v3.1.0 新增）
  degraded-write:
    enabled: true                 # 允许降级写入
    min-replicas: 1               # 降级模式下的最小副本数
    track-for-sync: true          # 记录降级写入以便后续同步

  virtualNodesPerNode: 150

  # 可选：域详细配置
  domains:
    - name: domain-a
      minNodes: 1
      acceptsWrites: true
    - name: domain-b
      minNodes: 1
      acceptsWrites: true
    - name: standby
      minNodes: 0
      acceptsWrites: false

  nodes:
    - name: node-a1
      endpoint: http://minio-a:9000
      faultDomain: domain-a
      weight: 100
    - name: node-b1
      endpoint: http://minio-b:9000
      faultDomain: domain-b
      weight: 100

  # 副本一致性修复配置
  consistency:
    repair:
      enabled: true               # 是否启用定时修复
      cron: "0 */15 * * * ?"      # 每 15 分钟执行
      batch-size: 100
      lock-timeout-seconds: 600

  # 数据再平衡配置
  rebalance:
    enabled: true                 # 是否启用自动再平衡
    rate-limit-per-second: 10     # 每秒最大复制对象数
    cleanup-source: false         # 再平衡后是否删除源数据
```

> **注意**: `active-domains` 为必填项，启动时会校验。单域开发模式只需配置一个域名。

### 配额治理

按用户和按租户的存储配额管控：

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `quota.enforcement-mode` | 执行模式 | `SHADOW`（仅记录日志，不拒绝） |
| `quota.rollout.strategy` | 灰度策略 | `TENANT_WHITELIST` |
| `quota.rollout.enforce-tenant-whitelist` | 执行配额的租户 ID（逗号分隔） | _（空 = 全局 `ENFORCE` 时全部租户生效）_ |
| `quota.rollout.force-shadow` | 强制所有租户使用 SHADOW 模式 | `false` |

> **提示**：建议先使用全局 `SHADOW` 模式观察配额使用情况，不会拒绝上传。
> 全局 `ENFORCE` 下空白名单表示全部租户生效；如需控制灰度范围，使用非空白名单或 `force-shadow=true`。

### 签名证明发行方

签名 Proof ZIP 使用独立 Ed25519 key，默认关闭并失败关闭。该配置禁止回退到 `JWT_KEY`、文件信封 master key 或区块链 RPC token。

| 环境变量 | 说明 | 默认值 |
|------|------|--------|
| `PROOF_SIGNING_ENABLED` | 是否允许新签发/历史重建 | `false` |
| `PROOF_SIGNING_KEY_ID` | 稳定 key 标识，只允许字母、数字、点、下划线和连字符，最多 64 字符 | 空 |
| `PROOF_SIGNING_KEY_VERSION` | 正整数 key 版本；轮换时必须递增 | `1` |
| `PROOF_SIGNING_KEY_STATUS` | 新签发必须为 `ACTIVE` | `DISABLED` |
| `PROOF_SIGNING_PRIVATE_KEY_PKCS8` | Base64 或 PEM 包装的 Ed25519 PKCS#8 私钥 | 空 |
| `PROOF_SIGNING_PUBLIC_KEY_SPKI` | 与私钥配对的 Base64 或 PEM X.509 SPKI 公钥 | 空 |

启用前必须同时配置匹配的 PKCS#8/SPKI、非空 key ID、正版本和 `ACTIVE` 状态。启动配置本身不会打印密钥；首次签发还会把 `(keyId, keyVersion)` 原子注册到全局 key 表。轮换时保留旧公开材料，把新密钥配置为新的更高版本，禁止复用同一 ID/version 绑定不同 SPKI。私钥应由部署密钥管理系统注入，不应写入 Git、日志或异常。

## 定时任务配置

### 分享清理

自动将过期分享标记为无效：

```yaml
share:
  cleanup:
    interval: 300000  # 每 5 分钟检查一次（毫秒）
```

使用分布式锁防止多实例部署时重复执行。

### 文件清理

清理保留期满后的软删除文件：

```yaml
file:
  cleanup:
    retention-days: 30      # 软删除文件保留天数
    batch-size: 100         # 每批处理文件数
    cron: "0 0 3 * * ?"     # 每天凌晨 3 点执行
```

## 文件密钥包封配置

新文件数据密钥由显式配置的 `file.key-envelope.active-provider` 包封。历史读取不会回退到当前 provider，而是严格按信封中持久化的 provider ID 与合同版本路由。

本地开发可使用 `local` 合同 v1，它保持历史 AES-GCM 信封格式不变。基础 profile 仅为本地开发兼容允许复用 `JWT_KEY`：

```yaml
file:
  key-envelope:
    active-provider: local
    active-provider-contract-version: 1
    providers:
      local:
        key-id: local-file-key-v1
        historical-key-ids: ${FILE_KEY_ENVELOPE_LOCAL_HISTORICAL_KEY_IDS:}
        master-key: ${FILE_KEY_ENVELOPE_MASTER_KEY:${JWT_KEY:}}
```

生产 profile 不提供 JWT 回退。若显式选择 `local`，`FILE_KEY_ENVELOPE_MASTER_KEY` 必须是与 JWT 不同且至少 32 字符的独立值。切换 local key ID 时，必须把仍被历史信封引用的旧 ID 以逗号分隔加入 `FILE_KEY_ENVELOPE_LOCAL_HISTORICAL_KEY_IDS` allowlist，轮换完成后再移除。使用 Vault Transit 时，先创建 derived `aes256-gcm96` key，再配置：

```yaml
file:
  key-envelope:
    active-provider: vault-transit
    active-provider-contract-version: 1
    providers:
      vault-transit:
        address: https://vault.example.com
        token: ${FILE_KEY_ENVELOPE_VAULT_TOKEN}
        namespace: ${FILE_KEY_ENVELOPE_VAULT_NAMESPACE:}
        mount: transit
        key-name: record-platform-file-key
        key-version: 1
        allow-http: false
        connect-timeout: 2s
        request-timeout: 5s
```

应用 token 只需对目标 key 的 `transit/encrypt`、`transit/decrypt`、`transit/rewrap` 路径拥有 `update` 能力。所有历史信封完成轮换前必须保留旧 provider/key 版本。context 绑定、迁移与 HSM 部署边界见[密钥管理安全文档](../../security/key-management.md)，dry-run、APPLY、恢复、告警和退休流程见[自动密钥轮换运维手册](../../operations/key-rotation.md)。

## 前端配置

前端环境变量 (`platform-frontend/.env`):

| 变量 | 说明 |
|------|------|
| `PUBLIC_API_BASE_URL` | 后端 API 地址 |
| `PUBLIC_ENV` | 环境名称 |
| `PUBLIC_TENANT_ID` | 默认租户 ID |
