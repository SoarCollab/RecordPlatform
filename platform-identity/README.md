# RecordPlatform Identity Service

## 📖 项目概述

RecordPlatform Identity Service 是基于区块链的分布式存证平台的身份认证微服务，提供完整的用户身份管理、OAuth2.0授权、SSO单点登录、第三方登录集成、操作审计、流量监控等企业级功能。

## 🌟 核心特性

### 🔐 身份认证管理
- **多种登录方式**: 用户名/邮箱登录、第三方OAuth登录
- **用户生命周期**: 注册、激活、密码管理、账号注销
- **密码安全**: BCrypt加密，强度可配置，防暴力破解
- **会话管理**: 基于Sa-Token的无状态JWT会话管理

### 🔑 OAuth2.0 授权服务
- **完整授权流程**: 授权码模式、客户端凭证模式、刷新令牌
- **客户端管理**: 动态注册、更新、删除OAuth客户端
- **细粒度权限**: 基于scope的权限控制
- **令牌生命周期**: 自动过期、主动撤销、安全刷新

### 🌐 SSO单点登录
- **跨应用登录**: 一次登录，多应用访问
- **安全传输**: JWT令牌安全传输和验证
- **统一注销**: 单点注销，全局会话清理
- **客户端管理**: 已登录客户端列表和管理

### 🔗 第三方登录集成
- **多平台支持**: GitHub、Google、微信、QQ、微博等
- **账号绑定**: 第三方账号与本地账号关联
- **令牌管理**: 第三方访问令牌的存储和刷新
- **用户信息同步**: 第三方用户信息获取和更新

### 📬 验证码服务
- **多种类型**: 邮件验证码、短信验证码、图形验证码
- **智能防护**: 发送频率限制、IP限制、验证次数限制
- **多场景支持**: 注册、重置密码、修改邮箱、登录验证
- **配置灵活**: 验证码长度、有效期、发送限制可配置

### 📊 监控审计系统
- **操作日志**: 详细记录用户操作，支持风险等级分类
- **流量监控**: 实时API访问监控、异常流量检测
- **性能分析**: 响应时间统计、错误率分析、热点API排行
- **安全防护**: 黑名单管理、DDoS检测、地理位置异常检测

### 📈 用户数据分析
- **用户统计**: 注册趋势、活跃度分析、留存率计算
- **行为分析**: 用户操作模式、设备使用统计
- **地理分布**: 用户地理位置分布统计
- **增长分析**: 用户增长率、角色分布等

## 🛠️ 技术栈

### 核心框架
- **Java 21**: 最新LTS版本，性能优化和新特性支持
- **Spring Boot 3.2.11**: 企业级应用框架
- **Sa-Token 1.44.0**: 轻量级权限认证框架，支持多种模式
- **MyBatis Plus 3.5.9**: 强化版MyBatis，简化CRUD操作

### 数据存储
- **MySQL 8.0+**: 主数据库存储
- **Redis 6.0+**: 缓存和会话存储
- **连接池**: Druid 1.2.23 高性能数据库连接池

### 开发工具
- **Maven**: 项目构建和依赖管理
- **Lombok**: 简化代码开发
- **Knife4j**: API文档生成和测试
- **HuTool**: Java工具类库

### 监控集成
- **Spring Actuator**: 应用健康检查和监控
- **Prometheus**: 监控指标收集
- **自定义监控**: 业务指标和安全监控

## 🚀 快速开始

### 环境要求
```bash
Java: 21+
MySQL: 8.0+
Redis: 6.0+
Maven: 3.8+
```

### 1. 克隆项目
```bash
git clone https://github.com/SoarCollab/RecordPlatform.git
cd RecordPlatform/platform-identity
```

### 2. 数据库初始化
```bash
# 执行数据库初始化脚本
mysql -u root -p < src/main/resources/sql/complete_init.sql
```

### 3. 配置文件设置
```yaml
# src/main/resources/application-dev.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/platform_identity
    username: your_username
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
      password: your_redis_password
```

### 4. 启动服务
```bash
# 开发环境启动
mvn spring-boot:run -Dspring.profiles.active=dev

# 或者
java -jar platform-identity-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

### 5. 验证服务
```bash
# 健康检查
curl http://localhost:8081/identity/actuator/health

# API文档
http://localhost:8081/identity/doc.html
```

## 📋 主要API接口

### 认证管理 `/api/auth`
- `POST /api/auth/login` - 用户登录
- `POST /api/auth/logout` - 用户注销
- `POST /api/auth/register` - 用户注册
- `POST /api/auth/verify-code` - 发送验证码
- `POST /api/auth/reset-password` - 重置密码
- `GET /api/auth/user-info` - 获取用户信息

### OAuth2.0 `/oauth`
- `GET /oauth/authorize` - 获取授权页面
- `POST /oauth/authorize` - 确认授权
- `POST /oauth/token` - 获取访问令牌
- `GET /oauth/userinfo` - 获取用户信息
- `POST /oauth/revoke` - 撤销令牌

### SSO单点登录 `/api/sso`
- `GET /api/sso/authorize` - SSO授权检查
- `POST /api/sso/login` - SSO登录处理
- `POST /api/sso/logout` - SSO单点注销
- `GET /api/sso/userinfo` - 获取SSO用户信息

### 第三方登录 `/api/auth/third-party`
- `GET /api/auth/third-party/providers` - 获取支持的提供商
- `GET /api/auth/third-party/{provider}/authorize` - 获取授权URL
- `GET /api/auth/third-party/{provider}/callback` - 处理回调
- `POST /api/auth/third-party/{provider}/bind` - 绑定第三方账号

### 验证码服务 `/api/verify`
- `POST /api/verify/email/send` - 发送邮件验证码
- `POST /api/verify/sms/send` - 发送短信验证码
- `GET /api/verify/image/generate` - 生成图形验证码
- `POST /api/verify/email/verify` - 验证邮件验证码

### 用户管理 `/api/user`
- `GET /api/user/info` - 获取当前用户信息
- `POST /api/user/change-password` - 修改密码
- `POST /api/user/modify-email` - 修改邮箱
- `POST /api/user/avatar` - 更新头像

### 管理员功能 `/api/admin`
- `GET /api/admin/user-stats/**` - 用户统计分析
- `GET /api/admin/operation-logs/**` - 操作日志管理
- `GET /api/admin/traffic-monitor/**` - 流量监控管理
- `GET /api/admin/gateway/**` - 网关监控管理

## 🏗️ 架构设计

### 系统架构
```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Web Client    │    │  Mobile Client  │    │  Third Party    │
└─────────┬───────┘    └─────────┬───────┘    └─────────┬───────┘
          │                      │                      │
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────▼───────────────┐
                    │      API Gateway           │
                    │    (Authentication &       │
                    │     Authorization)         │
                    └─────────────┬───────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                       │                        │
┌───────▼────────┐    ┌─────────▼─────────┐    ┌─────────▼─────────┐
│ Identity       │    │   OAuth Service   │    │  Monitor Service  │
│ Service        │    │                   │    │                   │
└───────┬────────┘    └─────────┬─────────┘    └─────────┬─────────┘
        │                       │                        │
        └───────────────────────┼────────────────────────┘
                               │
             ┌─────────────────▼─────────────────┐
             │         Data Layer               │
             │  ┌─────────┐    ┌─────────────┐   │
             │  │  MySQL  │    │    Redis    │   │
             │  └─────────┘    └─────────────┘   │
             └─────────────────────────────────────┘
```

### 模块结构
```
platform-identity/
├── src/main/java/cn/flying/identity/
│   ├── annotation/              # 自定义注解
│   │   └── Log.java            # 操作日志注解
│   ├── aspect/                 # 切面编程
│   │   └── OperationLogAspect.java
│   ├── config/                 # 配置类
│   │   ├── SaTokenConfig.java  # Sa-Token配置
│   │   ├── MybatisPlusConfig.java
│   │   ├── RedisConfig.java
│   │   └── SwaggerConfig.java
│   ├── controller/             # 控制器层
│   │   ├── AuthController.java
│   │   ├── OAuthController.java
│   │   ├── SSOController.java
│   │   ├── UserController.java
│   │   └── admin/              # 管理员控制器
│   ├── dto/                    # 数据传输对象
│   │   ├── Account.java
│   │   ├── OAuthClient.java
│   │   ├── OperationLog.java
│   │   └── UserSession.java
│   ├── filter/                 # 过滤器
│   │   ├── TrafficMonitorFilter.java
│   │   └── EnhancedGatewayFilter.java
│   ├── mapper/                 # 数据访问层
│   │   ├── AccountMapper.java
│   │   ├── OAuthClientMapper.java
│   │   └── OperationLogMapper.java
│   ├── service/                # 业务逻辑层
│   │   ├── AuthService.java
│   │   ├── OAuthService.java
│   │   ├── VerifyCodeService.java
│   │   └── impl/               # 服务实现
│   └── util/                   # 工具类
│       ├── SecurityUtils.java
│       ├── IdUtils.java
│       └── JsonUtils.java
├── src/main/resources/
│   ├── application.yml         # 主配置文件
│   ├── application-dev.yml     # 开发环境配置
│   ├── application-prod.yml    # 生产环境配置
│   ├── sql/                    # 数据库脚本
│   │   ├── complete_init.sql   # 完整初始化脚本
│   │   └── quick_setup.sql     # 快速设置脚本
│   └── mapper/                 # MyBatis映射文件
└── target/                     # 编译输出目录
```

### 数据库设计
```sql
-- 核心表
account              # 用户账号表
oauth_client         # OAuth客户端表
oauth_code          # OAuth授权码表
user_session        # 用户会话表
third_party_account # 第三方账号绑定表

-- 监控表
operation_log       # 操作日志表
traffic_monitor     # 流量监控表
token_monitor       # Token监控表

-- 索引优化
idx_account_email_deleted    # 邮箱查询优化
idx_oauth_code_cleanup       # 过期清理优化
idx_operation_log_user_time  # 用户日志查询优化
idx_traffic_monitor_ip_time  # 流量分析优化
```

## 🔧 配置说明

### 核心配置项
```yaml
# Sa-Token配置
sa-token:
  token-name: Authorization
  timeout: 2592000              # 30天
  active-timeout: 1800          # 30分钟
  is-concurrent: true
  is-share: true
  jwt-secret-key: RecordPlatform

# 应用配置
platform:
  identity:
    app-info:
      name: 存证平台认证服务
      version: 1.0.0
      auth-framework: SA-Token
    password:
      strength: 12              # BCrypt强度
      min-length: 6
      max-length: 50
    verify-code:
      email-limit: 60           # 邮件发送间隔(秒)
      expire-minutes: 10        # 过期时间(分钟)

# OAuth配置
oauth:
  code:
    timeout: 300               # 授权码过期时间(秒)
  access-token:
    timeout: 3600             # 访问令牌过期时间(秒)
  refresh-token:
    timeout: 86400            # 刷新令牌过期时间(秒)

# 监控配置
operation:
  log:
    enabled: true
    async-enabled: true
    retention-days: 90

gateway:
  traffic:
    monitor:
      enabled: true
      retention-days: 7
    rate-limit:
      enabled: true
      global-requests-per-minute: 10000
      ip-requests-per-minute: 300
```

### 第三方登录配置
```yaml
# GitHub OAuth
oauth:
  github:
    client-id: your_github_client_id
    client-secret: your_github_client_secret

# Google OAuth
  google:
    client-id: your_google_client_id
    client-secret: your_google_client_secret

# 微信 OAuth
  wechat:
    app-id: your_wechat_app_id
    app-secret: your_wechat_app_secret
```

## 🚀 部署指南

### 本地开发部署
```bash
# 1. 启动MySQL和Redis
docker-compose up -d mysql redis

# 2. 初始化数据库
mysql -u root -p < src/main/resources/sql/complete_init.sql

# 3. 启动应用
mvn spring-boot:run -Dspring.profiles.active=dev
```

### Docker部署
```dockerfile
# Dockerfile
FROM openjdk:21-jre-slim
VOLUME /tmp
COPY platform-identity-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app.jar"]
```

```yaml
# docker-compose.yml
version: '3.8'
services:
  platform-identity:
    image: platform-identity:latest
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/platform_identity
      - SPRING_DATA_REDIS_HOST=redis
    depends_on:
      - mysql
      - redis
      
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: your_password
      MYSQL_DATABASE: platform_identity
    volumes:
      - mysql_data:/var/lib/mysql
      
  redis:
    image: redis:6.2-alpine
    command: redis-server --requirepass your_password
    volumes:
      - redis_data:/data

volumes:
  mysql_data:
  redis_data:
```

### 生产环境部署
```bash
# 1. 构建项目
mvn clean package -Dmaven.test.skip=true

# 2. 启动服务
java -jar \
  -Dspring.profiles.active=prod \
  -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  platform-identity-0.0.1-SNAPSHOT.jar
```

## 🔒 安全特性

### 密码安全
- **BCrypt加密**: 使用可配置强度的BCrypt算法
- **盐值随机**: 每个密码使用不同的随机盐值
- **防暴力破解**: 登录失败次数限制和时间窗口限制

### Token安全
- **JWT签名**: 使用HMAC-SHA256算法签名
- **过期控制**: 访问令牌和刷新令牌分别管理过期时间
- **撤销机制**: 支持主动撤销令牌
- **刷新安全**: 刷新令牌一次性使用

### 接口安全
- **CORS配置**: 严格的跨域请求控制
- **频率限制**: API访问频率限制和IP限制
- **权限验证**: 基于角色和权限的访问控制
- **参数验证**: 严格的请求参数验证

### 监控安全
- **异常检测**: 实时检测异常访问模式
- **黑名单**: 自动和手动黑名单管理
- **审计日志**: 完整的操作审计和安全事件记录

## 📊 监控指标

### 系统指标
- **JVM监控**: 内存使用、GC情况、线程状态
- **数据库监控**: 连接池状态、查询性能、慢查询
- **Redis监控**: 内存使用、命中率、连接数
- **HTTP监控**: 请求量、响应时间、错误率

### 业务指标
- **用户指标**: 注册量、活跃用户、登录成功率
- **OAuth指标**: 授权成功率、令牌使用量、客户端活跃度
- **安全指标**: 登录失败次数、异常访问、风险评分

### 自定义监控
```java
// 示例：自定义监控指标
@Component
public class CustomMetrics {
    
    @EventListener
    public void onLoginSuccess(LoginSuccessEvent event) {
        // 记录登录成功指标
        Metrics.counter("user.login.success",
            "method", event.getLoginMethod(),
            "location", event.getLocation()
        ).increment();
    }
}
```

## 🤝 开发指南

### 代码规范
- **阿里巴巴Java开发规范**: 遵循阿里巴巴Java编码规范
- **注释规范**: 使用JavaDoc注释，描述方法功能、参数、返回值
- **命名规范**: 类名使用驼峰命名，方法名动词开头
- **异常处理**: 统一的异常处理机制，避免空catch块

### 测试规范
```java
// 单元测试示例
@SpringBootTest
class AuthServiceTest {
    
    @Autowired
    private AuthService authService;
    
    @Test
    void testLogin_Success() {
        // Given
        String username = "test@example.com";
        String password = "password123";
        
        // When
        Result<String> result = authService.login(username, password);
        
        // Then
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
    }
}
```

### 提交规范
```bash
# 提交格式
<type>(<scope>): <subject>

# 类型说明
feat: 新功能
fix: 修复bug
docs: 文档更新
style: 代码格式调整
refactor: 代码重构
test: 测试相关
chore: 构建过程或辅助工具的变动

# 示例
feat(auth): 添加第三方登录支持
fix(oauth): 修复令牌刷新逻辑错误
docs(readme): 更新部署指南
```

## 📞 技术支持

### 联系方式
- **项目地址**: https://github.com/SoarCollab/RecordPlatform
- **问题反馈**: 通过GitHub Issues提交问题
- **技术讨论**: 查看项目Wiki获取更多信息

### 常见问题

#### 1. 服务启动失败
```bash
# 检查数据库连接
spring.datasource.url=jdbc:mysql://localhost:3306/platform_identity?serverTimezone=UTC&useSSL=false

# 检查Redis连接
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

#### 2. Token验证失败
```bash
# 确认Sa-Token配置
sa-token.jwt-secret-key=your_secret_key
sa-token.token-name=Authorization
```

#### 3. 第三方登录失败
```bash
# 检查OAuth配置
oauth.github.client-id=your_client_id
oauth.github.client-secret=your_client_secret

# 确认回调URL配置正确
```

#### 4. 邮件发送失败
```bash
# 检查邮件服务器配置
spring.mail.host=smtp.gmail.com
spring.mail.username=your_email@gmail.com
spring.mail.password=your_app_password
```

### 性能优化建议

#### 1. 数据库优化
- 合理使用索引，避免全表扫描
- 定期清理过期数据（日志、会话、令牌）
- 配置数据库连接池参数

#### 2. Redis优化
- 设置合适的过期时间
- 使用Redis集群提高可用性
- 监控内存使用情况

#### 3. 应用优化
- 使用异步处理提高响应速度
- 合理设置JVM参数
- 启用HTTP/2和压缩

## 📋 更新日志

### v1.0.0 (当前版本)
**🎉 首次发布**
- ✅ 完整的身份认证体系
- ✅ OAuth2.0授权服务
- ✅ SSO单点登录支持
- ✅ 第三方登录集成
- ✅ 验证码服务
- ✅ 操作审计日志
- ✅ 流量监控告警
- ✅ 用户数据分析
- ✅ 企业级安全特性

**🔧 技术特性**
- Java 21 + Spring Boot 3.2.11
- Sa-Token 1.44.0 权限框架
- MyBatis Plus 3.5.9 数据访问
- Redis 缓存和会话管理
- MySQL 8.0 数据存储
- Knife4j API文档

**📈 性能指标**
- 启动时间 < 30秒
- 平均响应时间 < 100ms
- 支持并发用户 > 10000
- 系统可用性 > 99.9%

---

**文档版本**: v1.0.0  
**维护团队**: RecordPlatform Team
