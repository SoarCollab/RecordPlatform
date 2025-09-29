# AlertService 告警服务配置说明

## 概述
AlertService 提供多渠道告警通知功能，支持邮件、短信、钉钉、企业微信等多种告警方式。

## 配置示例

### 1. 邮件告警配置

在 `application.yml` 或 `application-{profile}.yml` 中添加以下配置：

```yaml
# Spring Mail 配置（必须）
spring:
  mail:
    host: smtp.qq.com                # SMTP服务器地址
    port: 587                        # SMTP端口
    username: your-email@qq.com      # 发件人邮箱
    password: your-password           # 邮箱密码或授权码
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true
          ssl:
            trust: smtp.qq.com
    default-encoding: UTF-8

# 告警服务邮件配置
api:
  gateway:
    alert:
      email:
        enabled: true                 # 启用邮件告警
        from: alert@system.com        # 发件人地址（显示名称）
        recipients:
          critical: admin@company.com,cto@company.com      # CRITICAL级别接收人（逗号分隔）
          error: ops@company.com,dev-lead@company.com      # ERROR级别接收人
          warning: monitor@company.com                     # WARNING级别接收人
          info: logs@company.com                          # INFO级别接收人
```

### 2. 短信告警配置（使用SMS4J框架）

```yaml
# SMS4J 框架配置
sms:
  config:
    supplier: alibaba                # 短信服务提供商 (alibaba/tencent/huawei/mock)
  restricted: false                  # false=启用短信，true=禁用短信

  # 阿里云短信配置示例
  alibaba:
    access-key-id: your-access-key
    access-key-secret: your-secret
    region: cn-hangzhou
    sign-name: 您的签名
    template-code: SMS_123456789     # 短信模板ID

# 告警服务短信配置
api:
  gateway:
    alert:
      sms:
        enabled: true                 # 启用短信告警
        recipients:
          critical: 13800138000,13900139000    # CRITICAL级别接收人手机号（逗号分隔）
          error: 13800138001                    # ERROR级别接收人手机号
          warning: 13800138002                  # WARNING级别接收人手机号
          # INFO级别默认不发送短信
```

### 3. 钉钉告警配置

```yaml
api:
  gateway:
    alert:
      dingtalk:
        enabled: true                 # 启用钉钉告警
        webhook: https://oapi.dingtalk.com/robot/send?access_token=xxx  # 钉钉机器人webhook
        secret: SECxxx                # 钉钉机器人密钥（可选）
```

### 4. 企业微信告警配置

```yaml
api:
  gateway:
    alert:
      wechat:
        enabled: true                 # 启用企业微信告警
        webhook: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx  # 企业微信机器人webhook
```

### 5. 告警策略配置

```yaml
api:
  gateway:
    alert:
      suppress-window: 300            # 告警抑制窗口（秒），防止重复告警
      aggregate-threshold: 10         # 告警聚合阈值，达到此数量后批量发送
```

## 告警级别说明

- **CRITICAL（严重）**: 所有渠道都会发送（邮件、短信、钉钉、企业微信）
- **ERROR（错误）**: 发送邮件、钉钉、企业微信
- **WARNING（警告）**: 仅发送钉钉、企业微信
- **INFO（信息）**: 仅记录日志，不发送通知

## 使用方式

### 1. 注入服务
```java
@Resource
private AlertService alertService;
```

### 2. 发送告警
```java
// 简单方式
alertService.sendAlert("CPU_HIGH", "CPU使用率超过90%", "CRITICAL");

// 详细方式
AlertService.Alert alert = new AlertService.Alert();
alert.setType("MEMORY_HIGH");
alert.setLevel(AlertService.AlertLevel.ERROR);
alert.setTitle("内存使用率过高");
alert.setContent("当前内存使用率: 85%, 阈值: 80%");
alert.setSource("MONITOR");
alert.setTime(LocalDateTime.now());
alertService.sendAlert(alert);
```

## 注意事项

1. **邮件发送**:
   - 需要配置 spring.mail 相关属性
   - 某些邮箱需要使用授权码而非密码
   - 建议使用企业邮箱以避免发送限制

2. **短信发送**:
   - 需要在对应服务商申请并配置密钥
   - 短信模板需要预先在服务商处审核通过
   - 注意短信发送成本和频率限制

3. **钉钉/企业微信**:
   - 需要创建机器人并获取webhook地址
   - 建议设置安全验证（如IP白名单、关键词等）
   - 注意消息发送频率限制

4. **告警策略**:
   - 合理设置抑制窗口避免告警风暴
   - 根据业务需求调整聚合阈值
   - 定期清理告警记录避免内存占用

## 故障排查

### 邮件发送失败
- 检查 SMTP 服务器配置是否正确
- 确认邮箱是否开启了 SMTP 服务
- 验证授权码是否正确
- 查看防火墙是否阻止了 SMTP 端口

### 短信发送失败
- 检查短信服务商配置是否正确
- 确认账户余额是否充足
- 验证短信模板是否审核通过
- 查看 SMS4J 框架日志

### 钉钉/企业微信发送失败
- 验证 webhook 地址是否正确
- 检查机器人是否被禁用
- 确认网络是否可以访问外部API
- 查看消息格式是否符合要求