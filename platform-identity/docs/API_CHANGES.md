# Platform Identity API 变更文档

## 版本信息
- **版本**: v1.0.0
- **变更类型**: 重大更新 - 移除 Spring Security 依赖

## 概述

本次更新主要移除了 Spring Security 依赖，使用轻量级的 BCrypt 库进行密码加密，并优化了错误处理机制。

## 🔄 主要变更

### 1. 依赖变更
- ❌ **移除**: `spring-boot-starter-security`
- ✅ **新增**: `at.favre.lib:bcrypt:0.10.2`

### 2. 密码加密变更
- **旧方式**: Spring Security PasswordEncoder
- **新方式**: BCrypt 独立加密库
- **影响**: 密码加密算法保持兼容，但实现方式改变

### 3. 配置变更
- **新增配置文件**: `application-identity.yml`
- **新增配置项**: 详见 [配置说明](#配置说明)

## 📋 新增错误码

### 用户相关错误 (20001-29999)
| 错误码 | 枚举名称 | 错误信息 | 说明 |
|--------|----------|----------|------|
| 20006 | USER_PASSWORD_ENCRYPT_ERROR | 密码加密失败 | 密码加密过程中发生错误 |
| 20007 | USER_PASSWORD_VERIFY_ERROR | 密码验证失败 | 密码验证过程中发生错误 |

### SSO和OAuth错误 (60001-69999)
| 错误码 | 枚举名称 | 错误信息 | 说明 |
|--------|----------|----------|------|
| 60001 | SSO_ERROR | SSO认证失败 | SSO单点登录认证失败 |
| 60002 | SSO_UNKNOWN_ERROR | SSO未知错误 | SSO过程中发生未知错误 |
| 60003 | OAUTH_ERROR | OAuth认证失败 | OAuth认证过程失败 |
| 60004 | OAUTH_CODE_INVALID | OAuth授权码无效 | 提供的OAuth授权码无效或已过期 |
| 60005 | OAUTH_TOKEN_INVALID | OAuth令牌无效 | 提供的OAuth令牌无效或已过期 |

## 🔧 API 接口变更

### 1. 认证相关接口

#### 登录接口
- **路径**: `POST /api/auth/login`
- **变更**: 错误处理优化，使用新的错误枚举
- **示例响应**:
```json
{
  "code": 20002,
  "message": "账号不存在或密码错误",
  "data": null
}
```

#### 密码重置接口
- **路径**: `POST /api/auth/reset-password`
- **变更**: 密码加密方式改变，但接口保持兼容
- **新增错误码**: `USER_PASSWORD_ENCRYPT_ERROR` (20006)

### 2. SSO 相关接口

#### SSO 错误处理
- **路径**: `GET /api/sso/error`
- **变更**: 使用新的错误枚举 `SSO_UNKNOWN_ERROR`
- **示例响应**:
```json
{
  "code": 60002,
  "message": "SSO未知错误",
  "data": {
    "error": "unknown_error",
    "error_description": "SSO未知错误",
    "state": ""
  }
}
```

### 3. OAuth 相关接口

#### 令牌获取接口
- **路径**: `POST /oauth/token`
- **变更**: 增强错误处理，新增错误码
- **新增错误码**: 
  - `OAUTH_CODE_INVALID` (60004)
  - `OAUTH_TOKEN_INVALID` (60005)

### 4. 第三方登录接口

#### 回调处理
- **路径**: `GET /api/third-party/callback/{provider}`
- **变更**: 统一错误处理，使用 `ResultEnum`
- **影响**: 错误响应格式标准化

## ⚙️ 配置说明

### 新增配置项

```yaml
platform:
  identity:
    # 应用信息配置
    app-info:
      name: 存证平台认证服务
      version: 1.0.0
      auth-framework: SA-Token
      api-doc-url: http://localhost:8888/identity/doc.html
    
    # 密码配置
    password:
      strength: 12          # BCrypt 加密强度
      min-length: 6         # 最小密码长度
      max-length: 50        # 最大密码长度
    
    # 验证码配置
    verify-code:
      email-limit: 60       # 邮件验证码发送冷却时间（秒）
      expire-minutes: 10    # 验证码有效期（分钟）
      length: 6             # 验证码长度
```

### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `platform.identity.password.strength` | int | 12 | BCrypt 加密强度，范围 4-31 |
| `platform.identity.password.min-length` | int | 6 | 密码最小长度 |
| `platform.identity.password.max-length` | int | 50 | 密码最大长度 |
| `platform.identity.verify-code.email-limit` | int | 60 | 邮件验证码发送间隔（秒） |
| `platform.identity.verify-code.expire-minutes` | int | 10 | 验证码有效期（分钟） |

## 🔒 安全性改进

### 1. 密码加密
- **算法**: BCrypt
- **强度**: 可配置（默认12）
- **优势**: 
  - 独立于 Spring Security
  - 性能更好
  - 配置更灵活

### 2. 错误处理
- **标准化**: 所有错误使用枚举值
- **安全性**: 避免敏感信息泄露
- **一致性**: 统一的错误响应格式

## 📈 性能优化

### 1. 依赖精简
- 移除重量级的 Spring Security 依赖
- 减少应用启动时间
- 降低内存占用

### 2. 配置优化
- 集中化配置管理
- 支持环境变量覆盖
- 热配置更新支持

## 🔄 迁移指南

### 1. 客户端适配
- **错误码**: 更新错误码处理逻辑
- **响应格式**: 确保能正确解析新的错误响应
- **配置**: 更新相关配置项

### 2. 兼容性说明
- **密码验证**: 完全兼容，无需修改现有密码
- **API接口**: 保持向后兼容
- **Token格式**: 无变化

### 3. 升级步骤
1. 更新配置文件
2. 重启服务
3. 验证功能正常
4. 更新客户端错误处理逻辑

## 📞 技术支持

如有问题，请联系开发团队或查看详细文档：
- **API文档**: http://localhost:8888/identity/doc.html
- **配置文档**: [配置说明](./CONFIGURATION.md)
- **故障排除**: [故障排除指南](./TROUBLESHOOTING.md)
