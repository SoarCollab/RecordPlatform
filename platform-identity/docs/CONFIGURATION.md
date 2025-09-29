# API Gateway 配置指南

## 配置文件结构

Platform-Identity 模块使用 Spring Boot 的多环境配置，配置文件位于 `src/main/resources/` 目录：

```
src/main/resources/
├── application.yml          # 主配置文件
├── application-local.yml    # 本地开发环境（如果有）
├── application-dev.yml      # 开发环境
├── application-test.yml     # 测试环境（如果需要）
├── application-prod.yml     # 生产环境（如果需要）
└── application-template.yml # 配置模板文件
```

## 核心配置项

### 1. 基础配置

```yaml
spring:
  application:
    name: platform-identity  # 应用名称
  profiles:
    active: dev  # 激活的配置环境
    include:
      - redis
      - oauth
      - verify-code
      - identity
      - sms

server:
  port: 8888  # 服务端口
  servlet:
    context-path: /identity  # 应用上下文路径
  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/xml,text/plain
```

### 2. 数据库配置

```yaml
spring:
  datasource:
    druid:
      type: com.alibaba.druid.pool.DruidDataSource
      driver-class-name: com.mysql.cj.jdbc.Driver
      url: jdbc:mysql://${DB_HOST:192.168.5.100}:${DB_PORT:3306}/platform_identity?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true
      username: ${DB_USERNAME:platform_identity}
      password: ${DB_PASSWORD:your_password}
      
      # Druid 连接池配置
      initialSize: 5              # 初始化连接数
      minIdle: 5                  # 最小空闲连接数
      maxActive: 40               # 最大活动连接数
      
      # Druid 监控配置
      web-stat-filter:
        enabled: true
        url-pattern: /*
        exclusions: /druid/*,*.js,*.css,*.gif,*.jpg,*.bmp,*.png,*.ico
      
      stat-view-servlet:
        enabled: true
        url-pattern: /druid/*
        login-username: ${DRUID_USERNAME:admin}  # 监控页面用户名
        login-password: ${DRUID_PASSWORD:123456}  # 监控页面密码
        reset-enable: false
```

### 3. Redis 配置

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}     # Redis密码，没有密码可以不配置
      database: ${REDIS_DATABASE:0}    # 使用的数据库索引
      timeout: 5000ms                  # 连接超时时间
      
      lettuce:
        pool:
          max-active: 8     # 最大连接数
          max-idle: 8       # 最大空闲连接数
          min-idle: 0       # 最小空闲连接数
          max-wait: -1ms    # 获取连接最大等待时间
```

### 4. MyBatis Plus 配置

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true  # 开启驼峰命名
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # SQL日志输出
  
  global-config:
    banner: true  # 控制台mybatis-plus标记
    db-config:
      logic-delete-field: deleted       # 全局逻辑删除字段
      logic-delete-value: 1             # 逻辑已删除值
      logic-not-delete-value: 0         # 逻辑未删除值
      id-type: INPUT                    # ID生成策略
```

### 5. Sa-Token 认证框架配置

```yaml
sa-token:
  # token 名称（同时也是 cookie 名称）
  token-name: record-platform
  # JWT密钥 - 使用环境变量配置
  jwt-secret-key: ${SA_TOKEN_JWT_SECRET_KEY:record-platform-jwt-secret-key-dev-only}
  # token 有效期（单位：秒）默认30天
  timeout: 2592000
  # token 最低活跃频率（单位：秒）
  active-timeout: -1
  # 是否允许同一账号多地同时登录
  is-concurrent: true
  # 在多人登录同一账号时，是否共用一个 token
  is-share: false
  # token 风格
  token-style: uuid
  # token前缀
  token-prefix: Bearer
  # 是否输出操作日志
  is-log: false
  # 是否写入响应头
  is-write-header: true
```

## API Gateway 专属配置

### 1. ID生成器安全配置

```yaml
# 雪花算法配置
snowflake:
  worker-id: 1
  data-center-id: 20

# ID混淆安全配置
id:
  security:
    key: "${ID_SECURITY_KEY:record-platform-secure-key-2025}"
  monitor:
    threshold: 200  # 1分钟内单线程生成ID数量阈值
  mapping:
    expire-hours: 48  # ID映射缓存过期时间(小时)
```

### 2. 缓存过期时间配置

```yaml
cache:
  expire:
    sso:
      token: 7200  # SSO Token过期时间（秒），默认2小时
```

### 3. 异步任务配置

```yaml
async:
  task:
    core-pool-size: 5               # 核心线程数
    max-pool-size: 20               # 最大线程数
    queue-capacity: 100             # 队列容量
    thread-name-prefix: "async-task-"  # 线程名前缀
    keep-alive-seconds: 60          # 线程空闲时间（秒）
    await-termination-seconds: 60   # 等待时间（秒）
```

### 4. 操作日志监控配置

```yaml
operation:
  log:
    operation-log:
      enabled: true                # 是否启用审计日志
      async-enabled: true          # 是否启用异步记录
      retention-days: 90           # 日志保留天数
      log-request-params: true     # 是否记录请求参数
      log-response-result: false   # 是否记录响应结果
      sensitive-operations:        # 敏感操作列表
        - "DELETE"
        - "UPDATE"
        - "EXPORT"
      high-risk-modules:           # 高风险模块列表
        - "USER"
        - "ROLE"
        - "PERMISSION"
```

### 5. 网关流量监控配置

```yaml
gateway:
  traffic:
    # 流量监控配置
    monitor:
      enabled: true                 # 是否启用流量监控
      async-enabled: true           # 是否启用异步记录
      retention-days: 7             # 监控数据保留天数
      time-window: 60               # 统计时间窗口（秒）
      sampling-rate: 1.0            # 采样率（0.0-1.0）
    
    # 限流配置
    rate-limit:
      enabled: true                              # 是否启用限流
      global-requests-per-minute: 10000          # 全局每分钟最大请求数
      ip-requests-per-minute: 300                # 单IP每分钟最大请求数
      user-requests-per-minute: 600              # 单用户每分钟最大请求数
      api-requests-per-minute: 1000              # 单API每分钟最大请求数
      max-concurrent-connections-per-ip: 50      # 单IP最大并发连接数
      block-duration: 300                        # 限流后阻塞时间（秒）
    
    # 异常检测配置
    anomaly-detection:
      enabled: true                            # 是否启用异常检测
      response-time-threshold: 5000            # 响应时间异常阈值（毫秒）
      error-rate-threshold: 10.0               # 错误率异常阈值（百分比）
      geo-anomaly-enabled: true                # 地理位置检测
      time-anomaly-enabled: true               # 时间异常检测
      device-anomaly-enabled: true             # 设备指纹检测
      bot-detection-enabled: true              # 机器人检测
      ddos-threshold: 100                      # DDoS检测阈值（每秒请求数）
      risk-score-threshold: 70                 # 风险评分阈值
    
    # 拦截配置
    blocking:
      auto-block-enabled: true                 # 是否启用自动拦截
      blacklist-expire-hours: 24               # 黑名单过期时间（小时）
      progressive-block-enabled: true          # 渐进式拦截启用
      whitelist-ips: []                        # 白名单IP列表
      permanent-blacklist-ips: []              # 永久黑名单IP列表
      block-response-code: 429                 # 拦截响应码
      block-response-message: "Request blocked due to suspicious activity"
```

### 6. API Gateway 网关配置

```yaml
api:
  gateway:
    # 连接池配置
    pool:
      max-total: 500          # 最大连接数
      max-per-route: 50       # 每个路由的最大连接数
      connect-timeout: 5000   # 连接超时（毫秒）
      socket-timeout: 30000   # Socket超时（毫秒）
    
    # 缓存配置
    cache:
      l1:
        max-size: 10000           # L1缓存最大条目数
        expire-seconds: 60        # L1缓存过期时间（秒）
      l2:
        expire-seconds: 3600      # L2缓存过期时间（秒）
      enable-stats: true          # 启用缓存统计
    
    # 熔断器配置
    circuit:
      failure-rate-threshold: 50              # 失败率阈值（%）
      slow-call-rate-threshold: 50            # 慢调用率阈值（%）
      slow-call-duration-threshold: 1000      # 慢调用时长阈值（毫秒）
      sliding-window-size: 100                # 滑动窗口大小
      minimum-number-of-calls: 10             # 最小调用次数
      wait-duration-in-open-state: 60         # 开路状态等待时长（秒）
      rate-limit-qps: 100                     # QPS限流
      max-retry-attempts: 3                   # 最大重试次数
    
    # 告警配置
    alert:
      email:
        enabled: true         # 启用邮件告警
      dingtalk:
        enabled: true         # 启用钉钉告警
      suppress-window: 300    # 告警抑制窗口（秒）
      aggregate-threshold: 10 # 聚合阈值
```

### 7. Knife4j API 文档配置

```yaml
knife4j:
  enable: true
  production: false
  basic:
    enable: true
    username: admin
    password: 123456
  setting:
    language: zh-CN
    enable-home-custom: true
    home-custom-path: classpath:static/home.md
    enable-footer: true
    enable-footer-custom: true
    footer-custom-content: "Copyright © 2025 RecordPlatform Team. All rights reserved."
```

### 8. Dubbo 配置

```yaml
dubbo:
  application:
    name: RecordPlatform_identity
    logger: slf4j
    protocol: tri
    qos-port: 22331
    qos-enable: true
    qos-accept-foreign-ip: false
    check-serializable: false
    serialize-check-status: DISABLE
  protocol:
    name: tri
    port: 8091
    payload: 104857600  # 最大消息体大小
  registry:
    register-mode: instance
    address: nacos://localhost:8848
  provider:
    token: ${DUBBO_PROVIDER_TOKEN:record-platform-internal-token}
    filter: authFilter
    timeout: 5000
```

### 9. 邮件服务配置

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.163.com}
    username: ${MAIL_USERNAME:your_email@163.com}
    password: ${MAIL_PASSWORD:your_password}
    default-encoding: UTF-8
    port: 465
    properties:
      from: ${MAIL_FROM:your_email@163.com}
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
          ssl:
            enable: true
          socketFactory:
            class: javax.net.ssl.SSLSocketFactory
            fallback: false
          timeout: 10000
          connectiontimeout: 10000
```

### 10. 消息队列配置

```yaml
spring:
  rabbitmq:
    addresses: ${RABBITMQ_HOST:192.168.5.100}
    stream:
      port: 5672
    username: ${RABBITMQ_USERNAME:rabbitmq}
    password: ${RABBITMQ_PASSWORD:rabbitmq}
    virtual-host: /
```

### 11. OAuth2 配置

```yaml
oauth:
  code:
    timeout: 300                # 授权码超时时间（秒）
  access-token:
    timeout: 3600               # 访问令牌超时时间（秒）
  refresh-token:
    timeout: 86400              # 刷新令牌超时时间（秒）
  default:
    scope: read                 # 默认作用域
  security:
    use-bcrypt: false           # 是否使用BCrypt加密
    require-state: true         # 是否要求state参数
  redis:
    key-prefix: "oauth2:"       # Redis键前缀
```

### 12. 第三方认证服务配置

```yaml
third-party:
  auth:
    github:
      client-id: ${GITHUB_CLIENT_ID:}
      client-secret: ${GITHUB_CLIENT_SECRET:}
      auth-url: https://github.com/login/oauth/authorize
      token-url: https://github.com/login/oauth/access_token
      user-info-url: https://api.github.com/user
    google:
      client-id: ${GOOGLE_CLIENT_ID:}
      client-secret: ${GOOGLE_CLIENT_SECRET:}
      auth-url: https://accounts.google.com/o/oauth2/v2/auth
      token-url: https://oauth2.googleapis.com/token
      user-info-url: https://www.googleapis.com/oauth2/v2/userinfo
    wechat:
      app-id: ${WECHAT_APP_ID:}
      app-secret: ${WECHAT_APP_SECRET:}
      auth-url: https://open.weixin.qq.com/connect/qrconnect
      token-url: https://api.weixin.qq.com/sns/oauth2/access_token
      refresh-token-url: https://api.weixin.qq.com/sns/oauth2/refresh_token
      user-info-url: https://api.weixin.qq.com/sns/userinfo
```

### 13. 短信服务配置

```yaml
sms:
  config:
    supplier: ${SMS_SUPPLIER:mock}  # 短信供应商：mock, aliyun, tencent等
  restricted: ${SMS_RESTRICTED:true}  # 是否启用短信限制
```

### 14. 平台身份配置

```yaml
platform:
  identity:
    app-info:
      name: 存证平台认证服务
      version: 1.0.0
      auth-framework: SA-Token
      api-doc-url: ${IDENTITY_API_DOC_URL:http://localhost:8888/identity/doc.html}
    startup-check:
      enabled: true
      db-connection-timeout: 5
      redis-test-key: startup:check
      redis-test-value: ok
    password:
      strength: 12
      min-length: 6
      max-length: 50
    verify-code:
      email-limit: 60         # 邮件验证码发送间隔（秒）
      expire-minutes: 3       # 验证码过期时间（分钟）
      length: 6               # 验证码长度
```

## 环境特定配置

### 开发环境 (application-dev.yml)

```yaml
# 开发环境配置
spring:
  datasource:
    druid:
      url: jdbc:mysql://${DB_HOST:192.168.5.100}:${DB_PORT:3306}/platform_identity?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true
      username: ${DB_USERNAME:platform_identity}
      password: ${DB_PASSWORD:your_password}
  
  mail:
    host: ${MAIL_HOST:smtp.163.com}
    username: ${MAIL_USERNAME:your_email@163.com}
    password: ${MAIL_PASSWORD:your_password}
    port: 465
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
          ssl:
            enable: true
  
  rabbitmq:
    addresses: ${RABBITMQ_HOST:192.168.5.100}
    port: 5672
    username: ${RABBITMQ_USERNAME:rabbitmq}
    password: ${RABBITMQ_PASSWORD:rabbitmq}
    virtual-host: /
```

### 开发环境 (application-dev.yml)

```yaml
# 开发环境配置
spring:
  datasource:
    druid:
      url: jdbc:mysql://${DB_HOST:192.168.5.100}:${DB_PORT:3306}/platform_identity?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&useSSL=false&allowPublicKeyRetrieval=true
      username: ${DB_USERNAME:platform_identity}
      password: ${DB_PASSWORD:your_password}
  
  mail:
    host: ${MAIL_HOST:smtp.163.com}
    username: ${MAIL_USERNAME:your_email@163.com}
    password: ${MAIL_PASSWORD:your_password}
    
  rabbitmq:
    addresses: ${RABBITMQ_HOST:192.168.5.100}
    username: ${RABBITMQ_USERNAME:rabbitmq}
    password: ${RABBITMQ_PASSWORD:rabbitmq}
```

### 测试环境 (application-test.yml)

```yaml
# 测试环境配置
spring:
  datasource:
    url: jdbc:mysql://test-mysql.example.com:3306/platform_identity
    username: test_user
    password: ${DB_PASSWORD}

api:
  monitor:
    call-log:
      log-request-body: true
      log-response-body: true  # 测试环境记录响应体
```

### 生产环境 (application-prod.yml)

```yaml
# 生产环境配置
spring:
  datasource:
    url: jdbc:mysql://prod-mysql.example.com:3306/platform_identity
    username: prod_user
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 50  # 生产环境增加连接池大小

logging:
  level:
    root: WARN
    cn.flying.identity: INFO
  file:
    name: /var/log/platform-identity/app.log
    max-size: 100MB
    max-history: 30

# 生产环境安全配置
api:
  gateway:
    signature:
      enabled: true
    ip-whitelist:
      enabled: true
  key:
    aes-secret: ${AES_SECRET}  # 从环境变量读取
  monitor:
    call-log:
      log-request-body: false  # 生产环境不记录请求体
      log-response-body: false
```

## Actuator 监控配置

```yaml
management:
  endpoints:
    web:
      exposure:
        include: '*'  # 暴露所有端点
    enabled-by-default: true
  endpoint:
    health:
      show-details: always
  prometheus:
    metrics:
      export:
        enabled: true
```

## 配置优先级

配置加载优先级从高到低：

1. 命令行参数
2. 环境变量
3. application-{profile}.yml
4. application.yml

示例：
```bash
# 命令行参数覆盖配置
java -jar platform-identity.jar --server.port=8889 --spring.profiles.active=prod

# 环境变量覆盖配置
export SERVER_PORT=8889
export SPRING_PROFILES_ACTIVE=prod
java -jar platform-identity.jar
```

## 动态配置

某些配置支持动态修改，无需重启应用：

### 通过管理接口修改

```http
POST /api/admin/config/update
Content-Type: application/json
Authorization: Bearer {admin_token}

{
  "key": "api.rate-limit.global.limit",
  "value": "20000"
}
```

### 支持动态修改的配置项

- 限流阈值
- 缓存过期时间
- 监控开关
- 日志级别

## 配置验证

启动时会自动验证配置的合法性：

```java
@Configuration
@ConfigurationProperties(prefix = "api.gateway")
@Validated
public class ApiGatewayConfig {

    @NotNull(message = "API网关启用状态不能为空")
    private Boolean enabled;

    @Min(value = 60, message = "时间戳容差不能小于60秒")
    @Max(value = 600, message = "时间戳容差不能大于600秒")
    private Integer timestampTolerance;

    // ... 其他配置项
}
```

## 配置建议

### 1. 安全建议

- **生产环境必须修改**：
  - `id.security.key`：ID混淆密钥，使用强随机字符串
  - `sa-token.jwt-secret-key`：JWT密钥，使用环境变量配置
  - 数据库密码：使用环境变量
  - Redis密码：启用认证
  - Druid监控密码：使用强密码
  - Dubbo token：使用强随机字符串

- **生产环境必须启用**：
  - 流量监控和异常检测
  - 限流和熔断
  - HTTPS
  - 日志脱敏

### 2. 性能优化

- **数据库连接池**：
  - 开发环境：5-10个连接
  - 测试环境：10-20个连接
  - 生产环境：20-50个连接

- **Redis连接池**：
  - 根据并发量调整
  - 监控连接使用率

- **缓存配置**：
  - L1缓存：本地缓存，TTL 60秒
  - L2缓存：Redis缓存，TTL 3600秒
  - 监控缓存命中率

### 3. 监控建议

- 开发环境：开启详细日志，启用SQL日志
- 测试环境：记录请求响应，启用性能监控
- 生产环境：只记录关键信息，禁用SQL日志输出到控制台

## 故障排查

### 常见配置问题

1. **数据库连接失败**
```yaml
# 检查URL格式
url: jdbc:mysql://host:port/database?参数

# 检查时区设置
serverTimezone=Asia/Shanghai
```

2. **Redis连接失败**
```yaml
# 检查密码配置
password: your_password  # 空密码不要配置此项

# 检查超时设置
timeout: 5000ms  # 注意单位
```

3. **端口冲突**
```yaml
# 修改端口
server:
  port: 8082  # 使用其他端口
```

## 配置模板生成

可以使用配置生成工具快速创建配置文件：

```bash
# 生成配置模板
java -jar platform-identity.jar --generate-config --profile=prod

# 验证配置文件
java -jar platform-identity.jar --validate-config --config=application-prod.yml
```

---

*文档版本: v2.0.0*  
*最后更新: 2025-10-15*  
*Java 版本: 21*  
*Spring Boot 版本: 3.2.11*  
*Sa-Token 版本: 1.44.0*