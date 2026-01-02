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
1. POST /api/v1/auth/sse-token → 获取短期 Token（30 秒，一次性）
2. GET /api/v1/sse/connect?token=<token> → 建立 SSE 连接
```

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

### 分布式限流器

基于 Redis Lua 脚本的滑动窗口：

```
ALLOWED → 允许请求
RATE_LIMITED → 超过窗口限制
BLOCKED → 在封禁列表中
```

**容错**：Redis 不可用时允许请求。

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

## 安全检查清单

- [ ] `JWT_KEY` ≥32 字符且具有高熵值
- [ ] 生产环境启用 SSL
- [ ] 配置 CORS 白名单
- [ ] 启用限流
- [ ] 所有端点进行输入验证
- [ ] SQL 注入防护（参数化查询）
- [ ] XSS 防护（输出编码）
- [ ] 敏感数据不记录日志

