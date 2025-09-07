# Platform Identity 服务文档

## 📖 概述

Platform Identity 是存证平台的身份认证服务，提供用户注册、登录、OAuth2.0授权、SSO单点登录、第三方登录等功能。本服务基于 SA-Token 框架构建，使用轻量级的 BCrypt 库进行密码加密。

## 🚀 快速开始

### 1. 环境要求
- **Java**: 17+
- **MySQL**: 8.0+
- **Redis**: 6.0+
- **Maven**: 3.6+

### 2. 启动服务
```bash
# 1. 克隆项目
git clone https://github.com/SoarCollab/RecordPlatform.git

# 2. 进入项目目录
cd RecordPlatform/platform-identity

# 3. 配置数据库和Redis连接
# 编辑 src/main/resources/application-dev.yml

# 4. 启动服务
mvn spring-boot:run

# 5. 访问API文档
http://localhost:8888/identity/doc.html
```

### 3. 健康检查
```bash
# 检查服务状态
curl http://localhost:8888/identity/actuator/health

# 检查数据库连接
curl http://localhost:8888/identity/actuator/health/db

# 检查Redis连接
curl http://localhost:8888/identity/actuator/health/redis
```

## 📋 主要功能

### 🔐 用户认证
- **用户注册**: 支持邮箱验证码注册
- **用户登录**: 用户名/邮箱 + 密码登录
- **密码管理**: 密码重置、修改密码
- **会话管理**: 基于SA-Token的会话管理

### 🔑 OAuth2.0 授权
- **授权码模式**: 标准OAuth2.0授权码流程
- **客户端凭证模式**: 服务间认证
- **令牌管理**: 访问令牌和刷新令牌
- **权限范围**: 细粒度的权限控制

### 🌐 SSO 单点登录
- **跨域SSO**: 支持多域名单点登录
- **安全令牌**: 基于JWT的安全令牌
- **自动登录**: 无感知的自动登录体验

### 🔗 第三方登录
- **GitHub登录**: 支持GitHub OAuth登录
- **Google登录**: 支持Google OAuth登录
- **微信登录**: 支持微信开放平台登录
- **账号绑定**: 第三方账号与本地账号绑定

### 📊 监控审计
- **操作日志**: 详细的用户操作记录
- **流量监控**: API访问频率和性能监控
- **Token监控**: Token使用情况和安全监控
- **风险评估**: 基于行为的风险评分

## 📚 文档导航

### 📖 技术文档
- **[API变更文档](API_CHANGES.md)**: 详细的API变更说明和迁移指南
- **[错误码文档](ERROR_CODES.md)**: 完整的错误码列表和处理建议
- **[配置文档](CONFIGURATION.md)**: 详细的配置项说明和最佳实践
- **[故障排除指南](TROUBLESHOOTING.md)**: 常见问题诊断和解决方案

### 🔧 开发指南
- **API文档**: http://localhost:8888/identity/doc.html
- **健康检查**: http://localhost:8888/identity/actuator/health
- **配置示例**: 查看 `src/main/resources/application-*.yml`

## 🔄 版本信息

### 当前版本: v1.0.0

**主要更新**:
- ✅ 移除 Spring Security 依赖
- ✅ 使用轻量级 BCrypt 加密库
- ✅ 优化错误处理机制
- ✅ 集中化配置管理
- ✅ 完善API文档和错误码

**新增功能**:
- 🆕 新增密码加密服务 `PasswordService`
- 🆕 新增应用配置类 `ApplicationProperties`
- 🆕 新增错误码: 20006, 20007, 60001-60005
- 🆕 完善的文档体系

**性能优化**:
- ⚡ 减少应用启动时间
- ⚡ 降低内存占用
- ⚡ 提升密码加密性能

## 🏗️ 架构设计

### 技术栈
- **框架**: Spring Boot 3.x
- **认证**: SA-Token
- **数据库**: MySQL + MyBatis Plus
- **缓存**: Redis
- **加密**: BCrypt
- **文档**: Swagger/OpenAPI 3

### 模块结构
```
platform-identity/
├── src/main/java/cn/flying/identity/
│   ├── annotation/          # 自定义注解
│   ├── aspect/             # 切面处理
│   ├── config/             # 配置类
│   ├── controller/         # 控制器
│   ├── dto/                # 数据传输对象
│   ├── entity/             # 实体类
│   ├── mapper/             # 数据访问层
│   ├── service/            # 业务逻辑层
│   └── util/               # 工具类
├── src/main/resources/
│   ├── application*.yml    # 配置文件
│   └── mapper/             # MyBatis映射文件
└── docs/                   # 文档目录
```

### 数据库设计
- **account**: 用户账号表
- **oauth_client**: OAuth客户端表
- **oauth_code**: OAuth授权码表
- **oauth_access_token**: OAuth访问令牌表
- **third_party_account**: 第三方账号绑定表
- **operation_log**: 操作日志表

## 🔒 安全特性

### 密码安全
- **加密算法**: BCrypt (强度可配置)
- **盐值**: 自动生成随机盐值
- **防暴力破解**: 登录失败次数限制

### Token安全
- **JWT签名**: 使用密钥签名验证
- **过期机制**: 可配置的过期时间
- **刷新机制**: 支持令牌刷新

### 接口安全
- **CORS配置**: 跨域请求控制
- **频率限制**: API访问频率限制
- **权限验证**: 基于角色的权限控制

## 📊 监控指标

### 关键指标
- **响应时间**: API接口平均响应时间
- **错误率**: 错误请求占总请求的比例
- **吞吐量**: 每秒处理的请求数量
- **并发用户**: 同时在线的用户数量

### 业务指标
- **注册用户**: 新注册用户数量
- **活跃用户**: 日活跃用户数量
- **登录成功率**: 登录成功的比例
- **第三方登录**: 各第三方平台登录占比

## 🚀 部署指南

### 开发环境
```bash
# 使用开发配置启动
mvn spring-boot:run -Dspring.profiles.active=dev
```

### 生产环境
```bash
# 构建JAR包
mvn clean package -Dmaven.test.skip=true

# 启动服务
java -jar -Dspring.profiles.active=prod platform-identity-1.0.0.jar
```

### Docker部署
```dockerfile
FROM openjdk:17-jre-slim
COPY platform-identity-1.0.0.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

## 🤝 贡献指南

### 开发规范
- **代码风格**: 遵循阿里巴巴Java开发规范
- **注释规范**: 使用JavaDoc注释
- **测试覆盖**: 单元测试覆盖率 > 80%
- **文档更新**: 及时更新相关文档

### 提交规范
```
feat: 新功能
fix: 修复bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建过程或辅助工具的变动
```

## 📞 技术支持

### 联系方式
- **项目地址**: https://github.com/SoarCollab/RecordPlatform
- **问题反馈**: 通过GitHub Issues反馈问题
- **技术交流**: 查看项目Wiki获取更多信息

### 常见问题
1. **服务启动失败**: 检查数据库和Redis连接配置
2. **Token验证失败**: 确认SA-Token配置正确
3. **第三方登录失败**: 检查第三方应用配置
4. **性能问题**: 查看监控指标和日志分析

### 文档更新
本文档会随着项目版本更新而持续维护，请关注最新版本的文档内容。

---

**文档版本**: v1.0.0  
**维护团队**: Platform Team
