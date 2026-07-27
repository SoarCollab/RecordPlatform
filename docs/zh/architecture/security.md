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

## 匿名公开分享租户边界

匿名面仅包含以下五条精确路由：

- `GET /api/v1/shares/{shareCode}/info`
- `GET /api/v1/shares/{shareCode}/files`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`
- `POST /api/v1/public/key-grants/consume`

它们既不需要 JWT，也不需要租户头。调用者控制的 `X-Tenant-ID` 会被忽略，不会成为权威 `TenantContext`。服务端通过匹配的 `shareCode` 元数据解析 owner tenant；只有这次分享元数据查询跨越租户隔离。文件元数据、key envelope、访问计数变更和分享访问审计随后都在 owner tenant 内执行。公开性、有效/取消/过期或未知状态、合法分享类型和文件归属校验继续失败关闭。当前模型没有分享密码字段；密码保护不属于本次变更。

匿名系统操作审计始终归 system tenant `0`；`share_access_log` 归解析出的 owner tenant，绝不采用调用者头。系统审计、分享审计与公开分享限流使用同一个规范化可信客户端 IP。分享创建/更新/取消、保存分享文件，以及登录态分享下载/解密路由仍受 Bearer 认证保护。

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

### 公开分享共享桶

公开 chunks 与 decrypt-info 端点按 `rate:limit:public:share-access:v2:ip:<canonical-ip>` 共享固定 30 次/60 秒额度。key 不包含 tenant、JWT 角色、端点方法或调用者提供的 header 值，因此修改 `X-Tenant-ID` 或在两个端点间交替都不能拆桶。可信代理边界与上文一致：只有立即 peer 命中已配置的数字 allowlist 时才处理转发头。前 30 次合计请求可以进入 controller；当前第 31 次通过现有响应包装保持 HTTP 200，并返回业务码 `70005`。Redis 结果不是 `1`（包括依赖异常）时会在 controller 执行前失败关闭。

### 分布式限流器

基于 Redis Lua 脚本的滑动窗口：

```
ALLOWED → 允许请求
RATE_LIMITED → 超过窗口限制
BLOCKED → 在封禁列表中
```

**通用工具容错**：可复用的分布式限流器在 Redis 不可用时允许请求；该策略不适用于 fail closed 的公共 proof 或公开分享注解桶。

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

### 数据密钥包封 provider

序列化文件数据密钥会从持久化文件元数据中移除，仅以包封后的 `file_key_envelope` 保存。provider-neutral 边界支持 `local` 合同 v1 和 `vault-transit` 合同 v1：

- 新写入只使用显式配置的 active provider；
- 读取严格使用持久化的 `(kmsProvider, providerContractVersion)`，禁止回退；
- `rp-file-envelope-aad-v1` 永久保持历史 local 字节顺序；
- local metadata 会校验 key ID、provider key version、包封算法和 context schema；仍被引用的旧 key ID 必须显式加入 allowlist；
- 外部 `rp-file-envelope-context-v2` 绑定 tenant、file、hash、recipient 和 suite，Vault 只接收规范字节的 Base64 SHA-256 摘要；
- 同名 Vault key 轮换使用 Transit `rewrap`，跨 provider 轮换才执行受控 unwrap/wrap；
- 审计与指标只记录稳定失败分类和 key ID 指纹，不记录原始 key ID、wrapped blob、明文数据密钥、token 或 Vault 错误体。

加密下载 metadata 现在返回短期 `grant-v1` 引用，不返回 plaintext 数据密钥。Redis 只保存引用摘要和不可变授权/信封绑定；Lua 状态转换允许首次消费和一次有界同会话重试。consume 会重新加载精确文件版本和精确 active 信封，因此轮换、撤销、分享取消、actor/session/tenant/client 漂移、过期或重放都会在释放密钥前失败关闭。grant 和瞬时密钥响应为 `no-store`，请求/响应 payload 日志关闭，浏览器不持久化 grant 或数据密钥。协议和浏览器内存边界见[密钥管理安全文档](../../security/key-management.md#7-下载密钥交付与暴露面)。

Vault Community 集成测试只证明 Transit API 合同，不证明 HSM 托管。生产 HSM-backed 部署需要 Vault Enterprise 的 PKCS#11 seal wrap 或 Managed Keys，以及相应许可证、硬件与高可用设计。

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

安全自动化存在两个不同的执行边界：

- **工作流**：`.github/workflows/security-poc.yml`
- **范围**：SAST 静态分析、SCA 依赖扫描、SBOM 生成
- **当前状态**：完整 `security-poc` 聚合仍为观察模式，不是 required check
- **工具链**：脚本和模板位于 `tools/security/`
- **Slither**（`.github/workflows/contract-security.yml`）— Solidity 智能合约静态分析，`.sol` 文件变更或 workflow 自身变更时触发，SARIF 结果上传至 GitHub Security 面板
- **Test Suite 阻断子集**（`.github/workflows/test.yml`）— 前端 `pnpm audit --audit-level high` 与显式 Trivy 已修复 High/Critical 扫描在超出配置门槛时会使 job 失败

这不表示所有安全观察都已阻断，也不表示仓库 advisory 为零。P6 仍负责统一剩余 scanner、advisory backlog、例外和发布执行策略。

## 安全检查清单

- [ ] `JWT_KEY` ≥32 字符且具有高熵值
- [ ] 生产环境启用 SSL
- [ ] 配置 CORS 白名单
- [ ] 启用限流
- [ ] 所有端点进行输入验证
- [ ] SQL 注入防护（参数化查询）
- [ ] XSS 防护（输出编码）
- [ ] 敏感数据不记录日志
