# 📋 RecordPlatform 存证平台认证服务

## 🚀 项目介绍

RecordPlatform 是一个基于区块链技术的分布式存证平台，本API文档提供认证服务的完整接口说明。

## 🔐 认证方式

本API使用**Sa-Token**进行身份认证，请在请求头中添加：

```http
Authorization: Bearer {your-token}
```

## 📊 主要功能模块

### 🔑 认证管理
- 用户注册、登录、注销
- 密码修改、重置
- 邮箱验证码

### 🌐 OAuth2.0 支持
- 标准OAuth2.0授权码模式
- 访问令牌管理
- 刷新令牌机制
- 客户端凭证模式

### 🔗 SSO单点登录
- 跨域单点登录
- 统一身份认证
- 会话管理

### 🌍 第三方登录
- GitHub登录
- Google登录
- 微信登录

### 🛡️ 安全监控
- 操作日志审计
- 流量监控
- 异常检测
- 风险评估

## 📞 联系我们

- **项目地址**: [https://github.com/wbq123789/RecordPlatform](https://github.com/wbq123789/RecordPlatform)

[//]: # (- **官方网站**: [https://platform.flyingcoding.cn]&#40;https://platform.flyingcoding.cn&#41;)

[//]: # (- **技术支持**: team@platform.flyingcoding.cn)