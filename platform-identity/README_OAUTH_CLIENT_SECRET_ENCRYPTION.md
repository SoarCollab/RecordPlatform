# OAuth 客户端密钥加密功能使用指南

## 🔒 功能概述

本系统实现了OAuth客户端密钥的BCrypt加密存储功能，提供以下特性：
- 📈 **向后兼容**：支持明文密钥的验证（现有客户端不受影响）
- 🔐 **BCrypt加密**：新注册的客户端密钥自动使用BCrypt加密存储
- 💪 **密钥强度验证**：确保客户端密钥满足安全要求

## ⚙️ 配置说明

### 1. 启用BCrypt加密

在 `application-oauth.yml` 中配置：

```yaml
oauth:
  security:
    # 启用BCrypt加密客户端密钥（推荐在生产环境启用）
    use-bcrypt: true
    # 要求HTTPS重定向（推荐在生产环境启用）
    require-https: true
    # 要求状态参数验证（防CSRF攻击）
    require-state: true
```

### 2. 配置说明

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `oauth.security.use-bcrypt` | `false` | 是否使用BCrypt加密客户端密钥 |
| `oauth.security.require-https` | `false` | 是否要求HTTPS重定向 |
| `oauth.security.require-state` | `true` | 是否要求状态参数 |
| `oauth.security.max-code-attempts` | `3` | 授权码最大重试次数 |

## 🚀 使用方法

### 1. 新客户端注册

当启用BCrypt加密后，新注册的客户端会自动使用加密存储：

```bash
# 注册新客户端
POST /oauth/client/register
Content-Type: application/json

{
  "clientKey": "my-app-client",
  "clientName": "我的应用",
  "redirectUris": "[\"https://my-app.com/callback\"]",
  "scopes": "read,write"
}
```

返回结果中的 `clientSecret` 是明文密钥，客户端需要保存此密钥：

```json
{
  "code": 200,
  "data": {
    "clientKey": "my-app-client", 
    "clientSecret": "aB3dF7jK9mN2pQ5sT8vY1zC4eG6hI0lO",  // 保存此密钥
    "clientName": "我的应用"
  }
}
```






## 🔧 开发环境配置

### 开发环境（明文模式）

```yaml
oauth:
  security:
    use-bcrypt: false  # 开发环境可以使用明文以便调试
    require-https: false
```

### 生产环境（加密模式）

```yaml
oauth:
  security:
    use-bcrypt: true   # 生产环境必须启用
    require-https: true
    require-state: true
```

## 🛡️ 安全最佳实践

### 1. 密钥强度要求

系统会自动验证客户端密钥强度：
- ✅ 最小长度：16位
- ✅ 必须包含字母和数字
- ✅ 自动生成的密钥长度：32位

### 2. 数据库存储

```sql
-- 明文密钥（不推荐）
client_secret: "myPlainTextSecret123"

-- BCrypt加密密钥（推荐）
client_secret: "$2a$12$aBcDeFgHiJkLmNoPqRsTuVwXyZ123456789..."
```

### 3. 客户端验证流程

```java
// 系统会自动检测密钥格式并选择合适的验证方式
if (oauthClientSecretService.isEncrypted(storedSecret)) {
    // BCrypt验证
    return passwordService.matches(rawSecret, storedSecret);
} else {
    // 向后兼容：明文比较
    return rawSecret.equals(storedSecret);
}
```

## 📋 安全运维建议

- 在生产环境启用 `use-bcrypt: true`
- 任何配置变更前先在测试环境验证
- 定期轮换客户端密钥（通过客户端管理功能生成新密钥并更新客户端配置）
- 监控登录失败率与异常流量
## 🔍 故障排除

### 常见问题

1. **Q: 启用加密后现有客户端无法登录？**
   
   A: 检查客户端是否使用了最新生成的密钥，并确认存量客户端已按新策略（BCrypt）重新生成并配置密钥。

2. **Q: 如何查看客户端密钥是否已加密？**
   
   A: BCrypt密钥格式：`$2a$12$...` （60字符）

3. **Q: 误删客户端密钥怎么办？**
   
   A: 通过客户端管理功能重新生成密钥，并在客户端侧更新配置。
### 日志监控

系统会记录关键操作日志：

```log
2024-01-01 10:00:00 INFO  客户端注册成功: clientKey=test-app, secretEncrypted=true
2024-01-01 10:02:00 WARN  客户端密钥验证失败: clientKey=invalid-app
```

## 📈 性能影响

- **BCrypt验证时间**：约1-5ms（可接受）
- **内存占用**：密钥长度增加（明文32字符 → 加密60字符）
- **数据库空间**：client_secret字段需要至少60字符

---

💡 **提示**：在生产环境中强烈建议启用BCrypt加密以提高安全性。
