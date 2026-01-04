# 配置说明

本指南介绍 RecordPlatform 的环境变量和配置选项。

## 环境变量

复制示例文件并自定义：

```bash
cp .env.example .env
vim .env
```

### 核心配置

| 分类 | 变量 | 说明 | 默认值 |
|------|------|------|--------|
| **数据库** | `DB_HOST` | MySQL 主机 | localhost |
| | `DB_PORT` | MySQL 端口 | 3306 |
| | `DB_NAME` | 数据库名 | RecordPlatform |
| | `DB_USERNAME` | 数据库用户 | root |
| | `DB_PASSWORD` | 数据库密码 | - |
| **Redis** | `REDIS_HOST` | Redis 主机 | localhost |
| | `REDIS_PORT` | Redis 端口 | 6379 |
| | `REDIS_PASSWORD` | Redis 密码 | - |
| **Nacos** | `NACOS_HOST` | Nacos 服务器 | localhost |
| | `NACOS_USERNAME` | Nacos 用户名 | nacos |
| | `NACOS_PASSWORD` | Nacos 密码 | nacos |
| **RabbitMQ** | `RABBITMQ_ADDRESSES` | RabbitMQ 地址 | localhost:5672 |
| | `RABBITMQ_USERNAME` | RabbitMQ 用户 | guest |
| | `RABBITMQ_PASSWORD` | RabbitMQ 密码 | guest |

### 安全配置

| 变量 | 说明 | 要求 |
|------|------|------|
| `JWT_KEY` | JWT 签名密钥 + ID 加密派生 | 至少 32 字符，高熵值 |

> **注意**: `ID_SECURITY_KEY` 自 v2.0 起已弃用，ID 加密密钥现在从 `JWT_KEY` 派生。

### 存储配置

S3 兼容存储通过 Nacos 配置。基本环境变量：

| 变量 | 说明 |
|------|------|
| `S3_ENDPOINT` | S3 端点 URL |
| `S3_ACCESS_KEY` | 访问密钥 |
| `S3_SECRET_KEY` | 私有密钥 |

故障域配置通过 Nacos 管理，支持运行时刷新。

### 区块链配置

| 变量 | 说明 | 示例 |
|------|------|------|
| `BLOCKCHAIN_ACTIVE` | 激活的链类型 | `local-fisco`, `bsn-fisco`, `bsn-besu` |
| `FISCO_PEER_ADDRESS` | FISCO 节点地址 | `127.0.0.1:20200` |
| `FISCO_STORAGE_CONTRACT` | Storage 合约地址 | `0x...` |
| `FISCO_SHARING_CONTRACT` | Sharing 合约地址 | `0x...` |

### SSL 配置（生产环境）

| 变量 | 说明 |
|------|------|
| `SERVER_SSL_KEY_STORE` | 密钥库路径 |
| `SERVER_SSL_KEY_STORE_PASSWORD` | 密钥库密码 |
| `SECURITY_REQUIRE_SSL` | 强制 HTTPS (true/false) |
| `SECURITY_HTTP_REDIRECT_PORT` | HTTP 重定向端口 |

### 服务端口配置

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVER_PORT` | 后端 REST API 端口 | 8000 |
| `DUBBO_FISCO_PORT` | FISCO Dubbo 服务端口 | 8091 |
| `DUBBO_STORAGE_PORT` | Storage Dubbo 服务端口 | 8092 |
| `DUBBO_HOST` | 服务注册 IP（用于 Docker 环境） | - |
| `QOS_BACKEND_PORT` | Backend QoS 管理端口 | 22330 |
| `QOS_FISCO_PORT` | FISCO QoS 管理端口 | 22331 |
| `QOS_STORAGE_PORT` | Storage QoS 管理端口 | 22332 |

> **注意**: `DUBBO_HOST` 在 Docker 环境中非常重要，确保服务注册使用可访问的 IP 而非 Docker 网桥 IP。

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
| `KNIFE4J_USERNAME` | Knife4j/Swagger UI 用户名 | admin |
| `KNIFE4J_PASSWORD` | Knife4j/Swagger UI 密码 | - |

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

动态配置通过 Nacos 管理。模板：`docs/nacos-config-template.yaml`

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
  # 格式：http://host:port（不带尾部斜杠）
  external-endpoint: http://10.1.0.2:9000

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

## 前端配置

前端环境变量 (`platform-frontend/.env`):

| 变量 | 说明 |
|------|------|
| `PUBLIC_API_BASE_URL` | 后端 API 地址 |
| `PUBLIC_ENV` | 环境名称 |
| `PUBLIC_TENANT_ID` | 默认租户 ID |

