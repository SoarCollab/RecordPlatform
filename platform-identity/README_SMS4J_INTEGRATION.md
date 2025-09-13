# SMS4J 短信框架集成说明

## 概述

本项目已成功集成SMS4J框架，用于统一管理短信发送功能。SMS4J是一个轻量级、高性能的短信聚合框架，支持多家短信服务提供商。

## 功能特性

- 🚀 **统一接口**: 提供统一的短信发送接口，支持多种短信服务提供商
- 🔄 **动态切换**: 支持动态切换短信服务提供商
- 📊 **多供应商支持**: 支持阿里云、腾讯云、华为云等主流短信服务商
- 🛡️ **高可用**: 支持短信发送失败重试和故障转移
- 📝 **详细日志**: 完整的短信发送日志记录
- 🧪 **测试模式**: 提供模拟短信发送功能，方便开发测试

## 项目变更

### 已移除的组件
- `cn.flying.identity.service.SmsService` (旧版本)
- `cn.flying.identity.service.impl.SmsServiceImpl` (旧版本)
- 原有的阿里云、腾讯云短信SDK直接调用代码

### 新增的组件
- **SMS4J框架依赖**: `org.dromara.sms4j:sms4j-spring-boot-starter:3.3.2`
- **新的短信服务**: 基于SMS4J重构的`SmsService`接口和实现
- **配置文件**: `application-sms.yml` - SMS4J配置

## 配置说明

### 1. 依赖配置

项目已在`pom.xml`中添加SMS4J依赖：

```xml
<dependency>
    <groupId>org.dromara.sms4j</groupId>
    <artifactId>sms4j-spring-boot-starter</artifactId>
    <version>3.3.2</version>
</dependency>
```

### 2. 短信服务配置

在`application-sms.yml`中配置短信服务提供商：

```yaml
sms:
  config-type: yaml
  restricted: false  # 短信发送功能开关，true=禁用，false=启用
  blends:
    # 阿里云短信配置
    alibaba:
      supplier: alibaba
      access-key-id: ${SMS_ALIBABA_ACCESS_KEY_ID:your_access_key_id}
      access-key-secret: ${SMS_ALIBABA_ACCESS_KEY_SECRET:your_access_key_secret}
      signature: ${SMS_ALIBABA_SIGNATURE:存证平台}
      template-id: ${SMS_ALIBABA_TEMPLATE_ID:SMS_123456789}
      
    # 腾讯云短信配置
    tencent:
      supplier: tencent
      secret-id: ${SMS_TENCENT_SECRET_ID:your_secret_id}
      secret-key: ${SMS_TENCENT_SECRET_KEY:your_secret_key}
      app-id: ${SMS_TENCENT_APP_ID:your_app_id}
      signature: ${SMS_TENCENT_SIGNATURE:存证平台}
      template-id: ${SMS_TENCENT_TEMPLATE_ID:123456}
      
    # 模拟短信（测试环境）
    mock:
      supplier: mock
      delay: 100
      fail-rate: 0.0
      
  config:
    supplier: ${SMS_SUPPLIER:mock}  # 当前使用的提供商
```

### 3. 环境变量配置

建议通过环境变量配置敏感信息：

```bash
# 阿里云短信配置
export SMS_ALIBABA_ACCESS_KEY_ID="your_access_key_id"
export SMS_ALIBABA_ACCESS_KEY_SECRET="your_access_key_secret"
export SMS_ALIBABA_SIGNATURE="存证平台"
export SMS_ALIBABA_TEMPLATE_ID="SMS_123456789"

# 腾讯云短信配置
export SMS_TENCENT_SECRET_ID="your_secret_id"
export SMS_TENCENT_SECRET_KEY="your_secret_key"
export SMS_TENCENT_APP_ID="your_app_id"
export SMS_TENCENT_SIGNATURE="存证平台"
export SMS_TENCENT_TEMPLATE_ID="123456"

# 选择当前使用的短信服务提供商
export SMS_SUPPLIER="mock"  # 可选值: alibaba, tencent, huawei, mock

# 短信发送功能开关控制
export SMS_RESTRICTED="true"  # true=禁用短信发送，false=启用短信发送
```

## 使用方法

### 1. 发送短信验证码

```java
@Resource
private SmsService smsService;

// 使用默认提供商发送短信验证码
Result<Boolean> result = smsService.sendVerifyCode("13800138000", "123456", "register");

// 指定提供商发送短信验证码
Result<Boolean> result = smsService.sendVerifyCode("13800138000", "123456", "register", "alibaba");
```

### 2. 发送普通短信

```java
// 使用默认提供商发送普通短信
Result<Boolean> result = smsService.sendMessage("13800138000", "您的验证码是123456");

// 指定提供商发送普通短信
Result<Boolean> result = smsService.sendMessage("13800138000", "您的验证码是123456", "tencent");
```

### 3. 检查服务可用性

```java
// 检查默认提供商是否可用
Result<Boolean> result = smsService.isServiceAvailable(null);

// 检查指定提供商是否可用
Result<Boolean> result = smsService.isServiceAvailable("alibaba");

// 获取可用的提供商列表
Result<List<String>> suppliers = smsService.getAvailableSuppliers();

// 获取当前默认提供商
String defaultSupplier = smsService.getDefaultSupplier();
```

## 短信类型说明

系统支持以下短信类型：

- `register`: 注册验证码
- `reset`: 密码重置验证码
- `modify`: 信息修改验证码
- `login`: 登录验证码

## 功能开关控制

### 短信发送开关

可以通过配置控制短信发送功能的启用/禁用状态：

```yaml
sms:
  restricted: true   # true=禁用短信发送，false=启用短信发送
```

**通过环境变量控制：**

```bash
# 禁用短信发送功能（默认）
export SMS_RESTRICTED=true

# 启用短信发送功能
export SMS_RESTRICTED=false
```

**功能说明：**

- 当`restricted=true`时，所有短信发送请求都会被跳过，但返回成功状态
- 当`restricted=false`时，短信发送功能正常工作
- 在开发、测试环境可以设置为`true`来避免误发短信
- 生产环境需要时可以设置为`false`来启用短信功能

**代码中检查开关状态：**

```java
@Resource
private SmsService smsService;

// 检查短信功能是否被禁用
boolean isRestricted = smsService.isSmsRestricted();
if (isRestricted) {
    log.info("短信发送功能已禁用");
}

// 检查服务可用性（会考虑开关状态）
Result<Boolean> available = smsService.isServiceAvailable(null);
```

## 测试模式

在开发和测试环境中，有两种方式避免实际发送短信：

### 方式1：禁用短信功能（推荐）
设置环境变量: `SMS_RESTRICTED=true`，完全禁用短信发送

### 方式2：使用模拟提供商
1. 设置环境变量: `SMS_SUPPLIER=mock`
2. 模拟短信会在日志中打印，不会实际发送
3. 可以配置模拟发送延迟和失败率

## 故障排查

### 1. 常见问题

- **短信发送失败**: 检查配置参数是否正确，特别是AccessKey、Secret等
- **提供商不可用**: 确认网络连接和服务商API状态
- **模板ID错误**: 确认使用的模板ID在服务商控制台中存在且已审核通过

### 2. 日志查看

系统会记录详细的短信发送日志：

```
2025-01-16 10:30:00 INFO  短信验证码发送成功，手机号: 138****8000, 类型: register, IP: 192.168.1.100, 提供商: mock
2025-01-16 10:30:01 ERROR 短信验证码发送失败，手机号: 138****8000, 类型: register, 提供商: alibaba, 错误: InvalidAccessKeyId
```

### 3. 监控建议

建议监控以下指标：

- 短信发送成功率
- 不同提供商的响应时间
- 短信发送失败的原因分布
- 每日短信发送量统计

## API接口

系统提供了完整的短信服务REST API，详见`VerifyCodeController`：

- `POST /api/verify/sms/send` - 发送短信验证码
- `POST /api/verify/sms/verify` - 验证短信验证码

## 安全建议

1. **敏感信息保护**: 不要在代码中硬编码AccessKey等敏感信息
2. **环境隔离**: 开发、测试、生产环境使用不同的短信账号
3. **频率限制**: 系统已内置短信发送频率限制，防止滥用
4. **监控告警**: 建议设置短信发送异常告警

## 升级说明

从原有短信服务迁移到SMS4J框架后：

1. **接口保持兼容**: `VerifyCodeService`的公开接口保持不变
2. **配置文件变更**: 需要更新短信相关配置
3. **环境变量**: 需要设置新的环境变量
4. **测试验证**: 建议在测试环境充分验证后再部署到生产环境

## 快速配置指南

### 立即禁用短信发送功能

如果您需要立即禁用短信发送功能，有以下几种方式：

**方式1：设置环境变量（推荐）**
```bash
export SMS_RESTRICTED=true
```

**方式2：修改配置文件**
在`application-sms.yml`中设置：
```yaml
sms:
  restricted: true
```

**方式3：启动参数**
```bash
java -jar app.jar --sms.restricted=true
```

### 重新启用短信发送功能

当需要启用短信发送功能时：

```bash
# 设置环境变量
export SMS_RESTRICTED=false

# 或修改配置文件
sms:
  restricted: false
```

**注意：** 修改配置后需要重启应用程序才能生效。

## 技术支持

- SMS4J官方文档: [https://sms4j.com/](https://sms4j.com/)
- 项目源码: [https://github.com/dromara/sms4j](https://github.com/dromara/sms4j)
- 问题反馈: 请在项目Issue中提交技术问题

---

更新时间: 2025-09-15  
维护者: RecordPlatform团队
