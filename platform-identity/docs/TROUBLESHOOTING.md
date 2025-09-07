# Platform Identity 故障排除指南

## 概述

本文档提供了 Platform Identity 服务常见问题的诊断和解决方案，帮助快速定位和解决问题。

## 🔍 问题诊断流程

### 1. 收集信息
- 错误现象描述
- 错误码和错误信息
- 操作步骤和时间
- 用户ID和请求参数
- 相关日志信息

### 2. 检查服务状态
```bash
# 检查服务是否运行
ps aux | grep platform-identity

# 检查端口是否监听
netstat -tlnp | grep 8888

# 检查服务健康状态
curl http://localhost:8888/identity/actuator/health
```

### 3. 查看日志
```bash
# 查看应用日志
tail -f logs/platform-identity.log

# 查看错误日志
grep "ERROR" logs/platform-identity.log

# 查看特定时间段日志
grep "2025-01-15 10:" logs/platform-identity.log
```

## 🚨 常见问题及解决方案

### 1. 服务启动问题

#### 问题：服务启动失败
**症状**：
- 应用无法启动
- 启动过程中抛出异常
- 端口无法绑定

**可能原因**：
- 配置文件错误
- 数据库连接失败
- Redis连接失败
- 端口被占用

**解决方案**：
```bash
# 1. 检查配置文件语法
yaml-lint application.yml

# 2. 检查数据库连接
mysql -h localhost -u username -p database_name

# 3. 检查Redis连接
redis-cli ping

# 4. 检查端口占用
lsof -i :8888
```

#### 问题：数据库连接失败
**错误信息**：
```
Could not open JDBC Connection for transaction
```

**解决方案**：
1. 检查数据库服务状态
2. 验证连接配置
3. 检查网络连通性
4. 确认用户权限

```yaml
# 正确的数据库配置示例
spring:
  datasource:
    druid:
      url: jdbc:mysql://localhost:3306/RecordPlatform?serverTimezone=Asia/Shanghai
      username: your_username
      password: your_password
```

### 2. 认证和授权问题

#### 问题：用户登录失败
**错误码**: 20002
**错误信息**: "账号不存在或密码错误"

**排查步骤**：
1. 确认用户名是否存在
2. 检查密码是否正确
3. 验证账号状态是否正常
4. 检查密码加密逻辑

```sql
-- 检查用户是否存在
SELECT * FROM account WHERE username = 'your_username' OR email = 'your_email';

-- 检查账号状态
SELECT id, username, email, deleted, create_time FROM account WHERE username = 'your_username';
```

#### 问题：Token验证失败
**错误码**: 70006
**错误信息**: "无效token"

**排查步骤**：
1. 检查Token格式
2. 验证Token是否过期
3. 确认SA-Token配置
4. 检查Token前缀

```java
// 检查Token信息
StpUtil.getTokenInfo();

// 检查登录状态
StpUtil.isLogin();

// 获取Token剩余有效期
StpUtil.getTokenTimeout();
```

### 3. OAuth和SSO问题

#### 问题：OAuth授权失败
**错误码**: 60003, 60004, 60005

**排查步骤**：
1. 检查客户端配置
2. 验证重定向URI
3. 确认授权码有效性
4. 检查Token状态

```sql
-- 检查OAuth客户端配置
SELECT * FROM oauth_client WHERE client_key = 'your_client_id';

-- 检查授权码状态
SELECT * FROM oauth_code WHERE code = 'your_auth_code';

-- 检查访问令牌
SELECT * FROM oauth_access_token WHERE access_token = 'your_token';
```

#### 问题：SSO登录失败
**错误码**: 60001, 60002

**排查步骤**：
1. 检查SSO配置
2. 验证客户端信息
3. 确认用户登录状态
4. 检查跨域设置

### 4. 第三方登录问题

#### 问题：GitHub登录失败
**症状**：
- 授权回调失败
- 获取用户信息失败
- 账号绑定失败

**排查步骤**：
1. 检查GitHub应用配置
2. 验证回调URL
3. 确认权限范围
4. 检查网络连接

```yaml
# GitHub配置检查
platform:
  identity:
    third-party:
      github:
        enabled: true
        client-id: ${GITHUB_CLIENT_ID}
        client-secret: ${GITHUB_CLIENT_SECRET}
        redirect-uri: http://localhost:8888/identity/api/third-party/callback/github
```

### 5. 验证码问题

#### 问题：邮件验证码发送失败
**错误码**: 90001

**排查步骤**：
1. 检查邮件服务配置
2. 验证SMTP设置
3. 确认邮箱有效性
4. 检查发送频率限制

```yaml
# 邮件配置检查
spring:
  mail:
    host: smtp.163.com
    username: your_email@163.com
    password: your_auth_code
    port: 465
    properties:
      from: your_email@163.com
```

#### 问题：验证码验证失败
**错误码**: 50004

**排查步骤**：
1. 检查验证码是否过期
2. 确认验证码输入正确
3. 验证Redis存储状态
4. 检查验证逻辑

```bash
# 检查Redis中的验证码
redis-cli get "verify:email:your_email@example.com"

# 检查验证码过期时间
redis-cli ttl "verify:email:your_email@example.com"
```

### 6. 性能问题

#### 问题：响应时间过长
**症状**：
- API响应缓慢
- 超时错误
- 系统负载高

**排查步骤**：
1. 检查数据库性能
2. 分析慢查询
3. 监控Redis性能
4. 检查网络延迟

```sql
-- 检查慢查询
SHOW PROCESSLIST;

-- 分析表性能
EXPLAIN SELECT * FROM account WHERE username = 'test';

-- 检查索引使用情况
SHOW INDEX FROM account;
```

#### 问题：内存使用过高
**排查步骤**：
1. 检查JVM内存配置
2. 分析内存泄漏
3. 优化缓存策略
4. 调整垃圾回收参数

```bash
# 检查JVM内存使用
jstat -gc <pid>

# 生成内存快照
jmap -dump:format=b,file=heap.hprof <pid>

# 分析内存使用
jmap -histo <pid>
```

## 🔧 调试工具和命令

### 1. 日志分析
```bash
# 实时查看日志
tail -f logs/platform-identity.log

# 搜索特定错误
grep -i "error" logs/platform-identity.log

# 统计错误频率
grep "ERROR" logs/platform-identity.log | wc -l

# 按时间过滤日志
sed -n '/2025-01-15 10:00/,/2025-01-15 11:00/p' logs/platform-identity.log
```

### 2. 数据库诊断
```sql
-- 检查连接数
SHOW STATUS LIKE 'Threads_connected';

-- 检查慢查询
SHOW VARIABLES LIKE 'slow_query_log';

-- 分析表状态
SHOW TABLE STATUS LIKE 'account';

-- 检查锁状态
SHOW ENGINE INNODB STATUS;
```

### 3. Redis诊断
```bash
# 检查Redis状态
redis-cli info

# 监控Redis命令
redis-cli monitor

# 检查内存使用
redis-cli info memory

# 分析慢查询
redis-cli slowlog get 10
```

### 4. 网络诊断
```bash
# 检查端口连通性
telnet localhost 8888

# 测试HTTP接口
curl -v http://localhost:8888/identity/api/auth/status

# 检查DNS解析
nslookup your-domain.com

# 测试网络延迟
ping your-server.com
```

## 📊 监控和告警

### 1. 关键指标监控
- **响应时间**: API接口响应时间
- **错误率**: 错误请求占比
- **吞吐量**: 每秒处理请求数
- **资源使用**: CPU、内存、磁盘使用率

### 2. 告警规则
```yaml
# 示例告警配置
alerts:
  - name: high_error_rate
    condition: error_rate > 5%
    duration: 5m
    
  - name: slow_response
    condition: response_time > 2s
    duration: 3m
    
  - name: high_memory_usage
    condition: memory_usage > 80%
    duration: 10m
```

### 3. 健康检查
```bash
# 应用健康检查
curl http://localhost:8888/identity/actuator/health

# 数据库健康检查
curl http://localhost:8888/identity/actuator/health/db

# Redis健康检查
curl http://localhost:8888/identity/actuator/health/redis
```

## 📞 获取技术支持

### 1. 问题报告模板
```
问题描述：
- 现象：
- 错误码：
- 错误信息：
- 发生时间：

环境信息：
- 服务版本：
- 操作系统：
- Java版本：
- 数据库版本：

重现步骤：
1. 
2. 
3. 

相关日志：
```

### 2. 联系方式
- **文档中心**: 查看相关技术文档
- **日志分析**: 提供详细的错误日志
- **远程协助**: 必要时提供远程协助

### 3. 紧急问题处理
- **生产环境故障**: 立即联系技术支持
- **数据安全问题**: 优先级最高处理
- **性能严重下降**: 快速响应和处理

## 📋 预防措施

### 1. 定期维护
- 定期清理日志文件
- 更新依赖库版本
- 优化数据库性能
- 备份重要数据

### 2. 监控告警
- 设置合理的告警阈值
- 建立完善的监控体系
- 定期检查系统状态
- 及时处理告警信息

### 3. 容量规划
- 评估系统负载能力
- 制定扩容计划
- 优化资源配置
- 预留足够的资源缓冲
