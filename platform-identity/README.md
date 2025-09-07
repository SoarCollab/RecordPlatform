# Platform Identity - 存证平台认证服务

## 项目简介

Platform Identity 是存证平台的统一认证服务，基于 SA-Token 框架构建，提供完整的用户认证、授权、单点登录等功能。

## 主要功能

### 🔐 用户认证
- 用户注册/登录
- 密码加密存储
- 邮件验证码
- 短信验证码（可扩展）
- 图形验证码

### 🎫 授权管理
- OAuth2.0 授权服务
- SSO 单点登录
- JWT Token 管理
- 权限验证
- 角色管理

### 🌐 第三方登录
- GitHub 登录
- Google 登录
- 微信登录
- QQ 登录（可扩展）
- 微博登录（可扩展）

### 📊 监控与审计
- 操作日志记录
- 流量监控
- 性能统计
- 异常检测
- 用户行为分析

### 🛡️ 安全防护
- 流量限制
- 异常检测
- IP 白名单
- 安全切面
- 审计日志

## 技术栈

- **框架**: Spring Boot 3.2.11
- **认证**: SA-Token 1.44.0
- **数据库**: MySQL 8.0 + MyBatis Plus
- **缓存**: Redis
- **文档**: Knife4j (Swagger)
- **工具**: Hutool, Lombok

## 快速开始

### 1. 环境要求

- JDK 21+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+

### 2. 数据库初始化

**重要：Platform Identity 现在使用独立数据库 `platform_identity`**

```sql
-- 方式1：快速部署（推荐用于开发环境）
source src/main/resources/sql/quick_setup.sql

-- 方式2：完整部署（推荐用于生产环境）
source src/main/resources/sql/complete_init.sql
```

**默认账户信息：**
- 管理员账户：admin / admin123
- OAuth客户端：platform-web-client / client_secret

### 3. 配置文件

复制配置模板并修改：

```bash
cp src/main/resources/application-template.yml src/main/resources/application-prod.yml
```

修改数据库和Redis连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/platform_identity  # 注意：使用独立数据库
    username: your_username
    password: your_password

  data:
    redis:
      host: localhost
      port: 6379
      password: your_redis_password
```

### 4. 启动应用

```bash
mvn spring-boot:run
```

### 5. 访问服务

- API文档: http://localhost:8081/identity/doc.html
- 健康检查: http://localhost:8081/identity/actuator/health
- 数据库监控: http://localhost:8081/identity/druid/

## API 接口

### 认证接口

```http
# 用户登录
POST /api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "123456"
}

# 用户注册
POST /api/auth/register
Content-Type: application/json

{
  "username": "newuser",
  "password": "password123",
  "email": "user@example.com",
  "code": "123456"
}

# 发送验证码
GET /api/verify/email/send?email=user@example.com&type=register
```

### OAuth2.0 接口

```http
# 获取授权码
GET /oauth/authorize?client_id=platform-web&redirect_uri=http://localhost:3000/callback&response_type=code&scope=read

# 获取访问令牌
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&code=AUTH_CODE&client_id=platform-web&client_secret=CLIENT_SECRET&redirect_uri=http://localhost:3000/callback
```

### SSO 接口

```http
# SSO 登录授权
GET /api/sso/authorize?client_id=platform-web&redirect_uri=http://localhost:3000/sso/callback&scope=read&state=random_state

# SSO 登录处理
POST /api/sso/login
Content-Type: application/x-www-form-urlencoded

username=admin&password=123456&client_id=platform-web&redirect_uri=http://localhost:3000/sso/callback
```

## 配置说明

### SA-Token 配置

```yaml
sa-token:
  token-name: satoken
  timeout: 2592000  # 30天
  is-concurrent: true
  is-share: true
  token-style: uuid
  jwt-secret-key: your-secret-key
```

### OAuth2.0 配置

```yaml
oauth:
  authorization-code-timeout: 600  # 授权码有效期
  access-token-timeout: 7200      # 访问令牌有效期
  refresh-token-timeout: 2592000  # 刷新令牌有效期
```

### 第三方登录配置

```yaml
oauth:
  github:
    client-id: your-github-client-id
    client-secret: your-github-client-secret
  google:
    client-id: your-google-client-id
    client-secret: your-google-client-secret
```

## 部署指南

### Docker 部署

```dockerfile
FROM openjdk:21-jdk-slim

COPY target/platform-identity-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Docker Compose

```yaml
version: '3.8'
services:
  platform-identity:
    build: .
    ports:
      - "8081:8081"
    environment:
      - DB_HOST=mysql
      - REDIS_HOST=redis
    depends_on:
      - mysql
      - redis
  
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: password
      MYSQL_DATABASE: record_platform
  
  redis:
    image: redis:6.0-alpine
```

## 监控与运维

### 健康检查

```bash
curl http://localhost:8081/identity/actuator/health
```

### 日志查看

```bash
tail -f logs/platform-identity.log
```

### 性能监控

访问 Druid 监控页面：http://localhost:8081/identity/druid/

## 开发指南

### 添加新的认证方式

1. 实现 `AuthenticationProvider` 接口
2. 注册到 Spring 容器
3. 配置相关参数

### 添加新的第三方登录

1. 在 `ThirdPartyAuthService` 中添加新的提供商
2. 实现相应的授权和用户信息获取逻辑
3. 配置客户端信息

### 自定义权限验证

1. 使用 `@SaCheckRole` 或 `@SaCheckPermission` 注解
2. 或实现自定义的权限验证逻辑

## 常见问题

### Q: 如何修改默认管理员密码？

A: 登录后通过 `/api/user/change-password` 接口修改，或直接在数据库中更新。

### Q: 如何配置邮件服务？

A: 在配置文件中设置 SMTP 服务器信息：

```yaml
spring:
  mail:
    host: smtp.qq.com
    username: your-email@qq.com
    password: your-auth-code
```

### Q: 如何启用第三方登录？

A: 在对应的第三方平台申请应用，获取 client_id 和 client_secret，然后在配置文件中设置。

## 许可证

Apache License 2.0

## 联系方式

- 作者: 王贝强
- 邮箱: admin@example.com
