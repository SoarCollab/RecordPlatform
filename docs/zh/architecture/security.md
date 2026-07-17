# 安全机制

RecordPlatform 的全面安全机制。

## 认证

### JWT Token

- **算法**: HMAC512（从 HMAC256 升级）
- **Issuer/Audience**: 每次请求验证
- **密钥验证**: 启动时检查密钥强度（≥32 字符，≥128 位熵）
- **黑名单**: 已登出的 Token 存储在 Redis

### 登录安全

| 保护措施 | 阈值 | 时长 |
|----------|------|------|
| IP 限流 | 10 次失败 | 5 分钟 |
| 账号锁定 | 5 次失败 | 15 分钟 |
| 强制验证码 | 3 次失败 | 直到成功 |

### SSE Token

EventSource 不支持自定义 Header，因此 SSE 使用 URL Token：

```
1. POST /api/v1/auth/tokens/sse → 获取短期 Token（30 秒，一次性）
2. GET /api/v1/sse/connect?token=<token>&x-tenant-id=<tenantHint> → 建立 SSE 连接
```

`X-Tenant-ID` 请求头、`x-tenant-id` query 和旧 `tenantId` query 都只是不可信的 namespace 提示。只有 Redis 原子消费短令牌且令牌 tenant 与提示一致后，服务端才会建立 `TenantContext`、请求审计属性和 MDC 身份。无效、过期、重放、损坏、租户不匹配或 Redis 异常的握手均不会创建 emitter；匿名失败审计固定落在 system tenant `0`，一次性 token 原文不会进入文本日志和数据库请求参数。

## 授权 (RBAC)

### 角色定义

| 角色 | 说明 | 默认权限 |
|------|------|----------|
| `user` | 普通用户 | 文件增删改查、分享、工单、消息 |
| `admin` | 管理员 | 所有权限 + 审计、强制删除 |
| `monitor` | 监控员 | 只读 + 审计日志查看 |

### 权限码格式

```
module:action
```

示例：
- `file:read` - 读取文件
- `file:admin` - 文件管理操作（跨用户、强制删除）
- `ticket:write` - 工单操作
- `system:audit` - 查看审计日志

### 使用方式

```java
// 权限检查
@PreAuthorize("hasPerm('file:admin')")

// 多权限（任一满足）
@PreAuthorize("hasAnyPerm('file:read', 'file:admin')")

// 角色检查
@PreAuthorize("isAdmin()")

// 资源所有权
@PreAuthorize("isOwner(#file.uid) or hasPerm('file:admin')")
```

### 资源所有权注解

```java
@RequireOwnership(
    resourceIdParam = "id",
    ownerIdField = "uid",
    resourceClass = File.class,
    adminBypass = true
)
public Result<File> getFile(@PathVariable Long id) { ... }
```

## 限流

### 分级限流

```java
@RateLimit(
    limit = 10,           // 普通用户: 10 次/分钟
    period = 60,          // 时间窗口（秒）
    adminLimit = 100,     // 管理员: 100 次/分钟
    type = LimitType.USER
)
```

### 限流类型

| 类型 | Key | 说明 |
|------|-----|------|
| `USER` | 用户 ID | 按用户限流（默认） |
| `IP` | IP 地址 | 按 IP 限流 |
| `API` | 端点 | 全局限流 |

### 公共 proof 共享桶

公共 proof 状态与历史公钥端点显式选择无租户的可信客户端 IP 模式。二者按 `rate:limit:public:proof-verification:v2:ip:<canonical-ip>` 共享固定 120 次/60 秒额度，即使请求携带有效的普通用户、管理员或监控员 JWT 也不会改变阈值。key 不包含 tenant、user、端点方法或原始 header；其他 `@RateLimit` 调用方继续保留 legacy tenant、角色和转发头行为。默认身份是规范化 direct socket peer，全部转发头都被忽略；可选的数字 trusted-proxy allowlist 默认空，启动时限制为 4096 字符/64 个网段，且仅在立即 peer 可信后从右向左解析一条 1024 字符/16 hops 的 XFF 链。Redis 结果不是 `1` 时（包括 `null`、`0` 或依赖异常）都不会执行 controller。

### 分布式限流器

基于 Redis Lua 脚本的滑动窗口：

```
ALLOWED → 允许请求
RATE_LIMITED → 超过窗口限制
BLOCKED → 在封禁列表中
```

**通用工具容错**：可复用的分布式限流器在 Redis 不可用时允许请求；该策略不适用于 fail closed 的公共 proof 注解桶。

## ID 混淆

外部 API 使用加密 ID，内部使用雪花 ID。

### 实现

- **算法**: AES-256-CTR + HMAC-SHA256（SIV 风格）
- **格式**: 前缀 + Base62（约 40 字符）
  - `E` 前缀: 实体 ID（文件、记录）
  - `U` 前缀: 用户 ID
- **密钥派生**: 从 `JWT_KEY` 使用 HKDF 派生

### 数据结构

```
明文 (16B): [version:1][type:1][id:8][padding:6]
密文 (42B): [SIV:16][AES-CTR(plaintext):16][HMAC:10]
输出: prefix + Base62(ciphertext) ≈ 40 字符
```

## 文件加密

### 支持的算法

| 算法 | 适用场景 | 性能 |
|------|----------|------|
| AES-256-GCM | AES-NI 硬件加速 | 1000+ MB/s |
| ChaCha20-Poly1305 | 容器、ARM 设备 | 稳定 |

### 配置

```yaml
file:
  encryption:
    algorithm: chacha20  # aes-gcm, chacha20, auto
    benchmark-on-startup: false
```

### 文件格式 (v2.0+)

```
[Header: 4B] [IV: 12B] [加密数据] [认证标签] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
```

Header: 魔数 `RP` (0x52 0x50) + 版本 (0x01) + 算法 (0x01=AES, 0x02=ChaCha20)

## HTTPS 强制

生产环境自动将 HTTP 重定向到 HTTPS：

```yaml
server:
  port: 443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12

security:
  require-ssl: true
  http-redirect-port: 80
```

## CI 安全流水线

自动化安全检测已配置为 GitHub Actions 工作流：

- **工作流**：`.github/workflows/security-poc.yml`
- **范围**：SAST 静态分析、SCA 依赖扫描、SBOM 生成
- **当前状态**：观察模式（仅提供信息，不阻塞 PR 合并）
- **工具链**：脚本和模板位于 `tools/security/`
- **Slither**（`.github/workflows/contract-security.yml`）— Solidity 智能合约静态分析，`.sol` 文件变更或 workflow 自身变更时触发，SARIF 结果上传至 GitHub Security 面板

> 该流水线计划在后续迭代中升级为发布阻断级别。

## 安全检查清单

- [ ] `JWT_KEY` ≥32 字符且具有高熵值
- [ ] 生产环境启用 SSL
- [ ] 配置 CORS 白名单
- [ ] 启用限流
- [ ] 所有端点进行输入验证
- [ ] SQL 注入防护（参数化查询）
- [ ] XSS 防护（输出编码）
- [ ] 敏感数据不记录日志
