# 密钥管理安全文档

## 概述

本文档描述 RecordPlatform 当前的密钥管理架构、安全假设、现有缓解措施以及已知限制。

## 1. 当前架构

### 1.1 密钥存储

- **初始密钥（initialKey）**：以加密分片形式存储在数据库中
- **存储位置**：`encryption_keys` 表，每个文件对应一条记录
- **分片机制**：密钥被拆分为多个加密块（chunks），分别存储

### 1.2 密钥派生

```
initialKey (主密钥)
    ↓
per-chunk key = KDF(initialKey, chunkIndex, salt)
    ↓
用于加密文件分片
```

- **派生函数**：基于 initialKey 和分片索引派生每个分片的加密密钥
- **算法支持**：AES-GCM（默认）、ChaCha20-Poly1305
- **熵来源**：SecureRandom 生成 initialKey，密码学安全伪随机数生成器

### 1.3 密钥生命周期

1. **生成**：文件上传时为每个文件生成唯一的 initialKey
2. **存储**：加密后写入数据库 `encryption_keys` 表
3. **使用**：文件下载时从数据库读取，派生分片密钥进行解密
4. **删除**：文件删除时级联删除对应的密钥记录（软删除）

### 1.4 架构限制

- **无 KMS**：密钥直接存储在数据库，未集成外部密钥管理服务
- **无硬件安全模块**：未使用 HSM 进行密钥保护
- **无密钥版本控制**：不支持密钥轮换和版本管理

## 2. 安全假设

本系统依赖以下安全假设正常运行：

### 2.1 基础设施安全

| 假设 | 实现方式 | 风险 |
|------|---------|------|
| **数据库静态加密** | MySQL 数据库启用 encryption at rest | 磁盘被盗时密钥仍受保护 |
| **网络传输加密** | TLS 1.2+ 用于所有服务间通信 | 中间人攻击无法窃取密钥 |
| **内存隔离** | JVM 进程隔离，敏感数据不写入日志 | 内存转储风险降低 |

### 2.2 访问控制

| 假设 | 实现方式 | 风险 |
|------|---------|------|
| **认证必需** | JWT token 强制验证，无匿名访问 | 未授权访问被拒绝 |
| **最小权限原则** | 数据库用户仅具备必要权限 | 权限提升攻击影响受限 |
| **租户隔离** | MyBatis Plus 拦截器自动注入 `tenant_id` | 跨租户数据泄露风险 |

### 2.3 运维安全

- **日志审计完整**：所有密钥访问操作记录在审计日志
- **定期备份**：数据库备份采用加密存储
- **漏洞修复**：依赖项定期更新（Dependabot 自动监控）

## 3. 当前缓解措施

### 3.1 审计与监控

```java
@OperationLog(
    module = OperationModule.FILE,
    operationType = OperationType.DOWNLOAD,
    description = "下载文件（触发密钥解密）"
)
```

- **审计日志覆盖范围**：
  - 密钥生成（文件上传）
  - 密钥读取（文件下载、分享）
  - 密钥删除（文件删除）
  - 权限变更（文件所有权转移）

- **日志内容**：
  - 操作时间戳
  - 用户 ID 和租户 ID
  - 操作类型（CREATE/READ/DELETE）
  - 资源 ID（文件 ID）
  - 操作结果（成功/失败及原因）

### 3.2 速率限制

```yaml
# application.yml
resilience4j:
  ratelimiter:
    instances:
      fileDownload:
        limit-for-period: 20      # 20 次请求
        limit-refresh-period: 60s # 每分钟
```

- **限流策略**：每用户每分钟最多 20 次下载请求
- **超限响应**：HTTP 429 Too Many Requests + Retry-After header
- **旁路保护**：Resilience4j 在 Dubbo 调用层面拦截，无法通过直接访问存储绕过

### 3.3 权限检查

```java
@RequireOwnership(
    resourceIdParam = "fileId",
    ownerIdField = "userId",
    resourceClass = File.class
)
public FileVO downloadFile(String fileId) {
    // 仅文件所有者或管理员可下载
}
```

- **归属验证**：AOP 切面自动验证资源所有权
- **管理员权限**：支持跨租户查询（需 `@TenantScope(ignoreIsolation=true)`）
- **分享链接**：独立权限体系，分享 token 验证 + 过期时间检查

### 3.4 密钥隔离

- **租户隔离**：`encryption_keys.tenant_id` 字段 + MyBatis 拦截器
- **用户隔离**：仅文件所有者可访问对应密钥
- **S3 路径隔离**：`/{tenantId}/{userId}/` 前缀防止路径遍历

## 4. 已知限制

### 4.1 密钥轮换

**当前状态**：❌ 不支持

- **问题**：密钥一旦生成无法更换，即使怀疑泄露也无法轮换
- **影响**：长期密钥暴露风险累积
- **workaround**：手动重新上传文件（生成新密钥）

### 4.2 硬件安全模块（HSM）

**当前状态**：❌ 未集成

- **问题**：密钥以软件形式存储在数据库，无法抵御高级持续性威胁（APT）
- **影响**：数据库 root 用户或磁盘物理访问可直接读取密钥
- **workaround**：依赖数据库静态加密 + 严格访问控制

### 4.3 单点故障

**当前状态**：⚠️ 数据库是唯一密钥存储

- **问题**：数据库不可用时所有加密文件无法解密
- **影响**：备份恢复时需同时恢复密钥数据，否则文件永久丢失
- **workaround**：
  - 数据库主从复制（已配置读写分离）
  - 定期加密备份（存储在独立位置）

### 4.4 密钥管理服务（KMS）

**当前状态**：❌ 未集成

- **问题**：无中心化密钥管理，无法统一审计和权限控制
- **影响**：密钥策略分散在应用代码中，难以统一修改
- **workaround**：依赖应用层权限检查 + 审计日志

### 4.5 密钥导出

**当前状态**：❌ 不支持

- **问题**：用户无法导出自己的密钥进行离线解密
- **影响**：平台锁定（vendor lock-in），用户必须通过平台 API 访问文件
- **workaround**：提供批量下载 API（解密后导出明文）

## 5. 路线图（v2.0）

### 5.1 KMS 集成

**目标**：集成外部 KMS（AWS KMS / Azure Key Vault / HashiCorp Vault）

**架构变更**：
```
当前: initialKey → Database (encrypted)
    ↓
未来: Data Encryption Key (DEK) → Database
      Key Encryption Key (KEK) → KMS
      
      解密流程: 
      1. 从 Database 读取 encrypted DEK
      2. 调用 KMS.decrypt(DEK, KEK_ID)
      3. 用 DEK 解密文件分片
```

**优势**：
- KEK 永不离开 KMS 硬件边界
- 中心化密钥策略管理
- 统一审计和合规性报告

### 5.2 密钥轮换

**目标**：支持密钥版本控制和自动轮换

**实现方案**：
```sql
-- 新增字段
ALTER TABLE encryption_keys 
  ADD COLUMN key_version INT NOT NULL DEFAULT 1,
  ADD COLUMN rotated_at DATETIME,
  ADD COLUMN previous_key_id BIGINT;

-- 轮换流程
1. 生成新 initialKey (version = N+1)
2. 读取旧密钥 (version = N) 解密文件
3. 用新密钥重新加密所有分片
4. 更新 encryption_keys 表
5. 保留旧密钥 90 天（用于回滚）
```

**触发条件**：
- 定期轮换（每 90 天）
- 安全事件触发（怀疑泄露）
- 合规要求（如 PCI DSS）

### 5.3 多区域密钥复制

**目标**：支持跨数据中心密钥同步

**实现方案**：
- 主区域 KMS 作为密钥源
- 从区域定期同步加密后的 DEK
- 区域间通信使用 TLS 1.3 + 客户端证书

### 5.4 密钥导出与离线解密

**目标**：允许用户导出密钥副本（加密）

**实现方案**：
```
1. 用户生成 GPG 公钥上传
2. 系统用用户公钥加密 DEK
3. 用户下载 encrypted_dek_bundle.gpg
4. 用户本地用 GPG 私钥解密，离线解密文件
```

**安全约束**：
- 仅文件所有者可导出
- 审计日志记录导出操作
- 导出操作触发邮件通知

## 6. 合规性考虑

### 6.1 GDPR（通用数据保护条例）

- **数据最小化**：仅存储必要的密钥元数据
- **被遗忘权**：文件删除时级联删除密钥
- **数据可移植性**：v2.0 提供密钥导出功能

### 6.2 等保 2.0（中国信息安全等级保护）

- **三级系统要求**：
  - ✅ 密钥存储加密
  - ✅ 访问控制和审计
  - ❌ 密钥备份与恢复（需人工流程）
  - ❌ 密钥轮换（v2.0 支持）

### 6.3 PCI DSS（支付卡行业数据安全标准）

- **不适用**：本系统不存储支付信息
- **参考实践**：密钥管理参考 PCI DSS 3.5/3.6 要求

## 7. 应急响应

### 7.1 密钥泄露事件

**检测**：
- 异常下载量（速率限制告警）
- 非工作时间大量密钥访问
- 跨租户访问尝试（审计日志）

**响应流程**：
1. 立即禁用受影响用户账户
2. 导出审计日志进行取证分析
3. 通知受影响用户
4. 评估影响范围（哪些文件可能被解密）
5. 手动轮换密钥（重新上传文件）

### 7.2 数据库泄露

**假设场景**：攻击者获取数据库备份

**影响评估**：
- ✅ 密钥以加密形式存储（依赖数据库静态加密）
- ❌ 如数据库主密钥同时泄露，所有文件密钥可被解密

**缓解措施**：
- 数据库备份使用独立加密密钥
- 备份文件存储在物理隔离的位置
- 定期测试备份恢复流程

## 8. 最佳实践建议

### 8.1 开发人员

- ❌ 禁止在日志中输出密钥内容
- ❌ 禁止在异常堆栈中包含密钥
- ✅ 使用 `@Slf4j` 时避免 `log.debug("key={}", key)`
- ✅ 密钥对象使用后立即清零（`Arrays.fill(keyBytes, (byte) 0)`）

### 8.2 运维人员

- ✅ 数据库用户使用最小权限原则
- ✅ 定期审查审计日志（每周）
- ✅ 数据库备份加密存储，密钥异地保管
- ❌ 禁止在生产环境执行 `SELECT * FROM encryption_keys`

### 8.3 安全审计

**月度检查**：
- 审计日志完整性验证
- 异常访问模式检测
- 权限配置审查

**季度检查**：
- 依赖项漏洞扫描（Trivy）
- 渗透测试（模拟密钥窃取攻击）
- 灾难恢复演练（备份恢复测试）

## 9. 参考资料

- [NIST SP 800-57: Key Management Recommendation](https://csrc.nist.gov/publications/detail/sp/800-57-part-1/rev-5/final)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)
- [AWS KMS Best Practices](https://docs.aws.amazon.com/kms/latest/developerguide/best-practices.html)

---

**文档版本**：v1.0  
**最后更新**：2026-06-14  
**维护者**：Security Team
