# RecordPlatform 系统演进规划 v2.0

> 更新日期: 2025-12-06
> 基于代码深度分析与现有改动整合

---

## 一、现状总结

### 1.1 架构概览

```
                         基础设施层
    ┌────────┐  ┌───────┐  ┌──────────┐  ┌───────┐  ┌────────────┐
    │ Nacos  │  │ MySQL │  │ RabbitMQ │  │ Redis │  │ MinIO 集群 │
    │ :8848  │  │ :3306 │  │  :5672   │  │ :6379 │  │   :9000    │
    └────┬───┘  └───┬───┘  └────┬─────┘  └───┬───┘  └─────┬──────┘
         │          │           │            │            │
         │    ┌─────┴───────────┴────────────┴────────────┘
         │    │
    ┌────┴────┴──────────────────────────────────────────────────┐
    │                    platform-api                            │
    │              (Dubbo 接口契约层)                             │
    │   BlockChainService, DistributedStorageService             │
    └──────────────────────────┬─────────────────────────────────┘
                               │ implements
         ┌─────────────────────┼─────────────────────┐
         │                     │                     │
         ▼                     │                     ▼
┌─────────────────┐            │            ┌─────────────────┐
│ platform-fisco  │            │            │  platform-minio │
│ (Dubbo Provider)│            │            │ (Dubbo Provider)│
│ FISCO BCOS SDK  │            │            │ MinIO 客户端    │
│ Port 8091       │            │            │ Port 8092       │
└────────┬────────┘            │            └────────┬────────┘
         │                     │                     │
         │      Dubbo RPC      ▼      Dubbo RPC      │
         │            ┌─────────────────┐            │
         └───────────►│ platform-backend│◄───────────┘
                      │ (Dubbo Consumer)│
                      │ REST API :8000  │
                      └─────────────────┘
                               │
                      ┌────────┴────────┐
                      │ FISCO BCOS Node │
                      │ Peer :20200     │
                      └─────────────────┘
```

**技术栈**: Java 21, Spring Boot 3.2.11, Dubbo 3.3.3 (Triple), MyBatis Plus 3.5.9, FISCO BCOS 3.8.0, MinIO 8.5.9

### 1.2 代码质量评估: 7.5/10

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | 8/10 | 分层清晰，Dubbo 集成规范 |
| 安全性 | 6/10 | 存在密钥泄露和 ID 可预测问题 |
| 容错能力 | 5/10 | 缺少重试、熔断机制 |
| 可观测性 | 6/10 | 日志基础完善，缺少分布式追踪 |
| 代码规范 | 8/10 | 命名规范，注释适度 |

---

## 二、已完成修复 (Current Changes)

以下问题已在当前代码改动中修复：

### 2.1 类型一致性修复 ✅

**问题**: `userId` 类型在系统中不一致 (String vs Long)

**修复范围**:
- `FileStorageEvent.java:15` - uid 字段改为 Long
- `File.java:33` - uid 字段改为 Long
- `FileServiceImpl.java` - 所有方法签名统一使用 Long
- `FileController.java`, `AccountController.java` 等控制器层

### 2.2 操作日志覆盖修复 ✅

**问题**: `OperationLogAspect` 忽略 `/api/file` 端点，核心业务无审计

**修复** (`OperationLogAspect.java:42`):
```java
// Before: ignores 包含 "/api/file"
// After: 移除 "/api/file"，改为仅忽略 "/api/system/logs"
private final Set<String> ignores = Set.of(
    "/favicon.ico", "/webjars", "/doc.html",
    "/swagger-ui", "/v3/api-docs", "/api/system/logs"
);
```

**额外改进** (`OperationLogAspect.java:268-279`):
- 过滤不可序列化参数 (MultipartFile, HttpServletRequest/Response)

### 2.3 线程安全修复 ✅

**问题**: `SimpleDateFormat` 非线程安全

**修复** (`Convert.java:15-17`):
```java
private static final DateTimeFormatter DATE_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
```

### 2.4 MDC 常量使用 ✅

**问题**: `JwtAuthenticationFilter` 使用硬编码字符串

**修复** (`JwtAuthenticationFilter.java:48-64`):
- 统一使用 `Const.ATTR_USER_ID` 和 `Const.ATTR_USER_ROLE`

---

## 三、待修复问题清单

### 3.1 P0 - 安全漏洞 (必须立即修复)

| 编号 | 问题 | 位置 | 影响 |
|------|------|------|------|
| P0-1 | sdk.key 私钥提交到 Git | `platform-fisco/src/main/resources/conf/sdk.key` | 区块链账户可被盗用 |
| P0-2 | 默认 JWT 密钥暴露 | `application-*.yml` | 任何人可伪造 Token |
| P0-3 | ID 混淆算法可预测 | `IdUtils.java:60-117` | 用户 ID 可被枚举 |
| P0-4 | CORS 允许所有来源 | `application.yml` origin: '*' | CSRF 攻击风险 |

### 3.2 P1 - 稳定性问题 (1-2周内修复)

| 编号 | 问题 | 位置 | 影响 |
|------|------|------|------|
| P1-1 | 无重试机制 | `FileServiceImpl.java:61` TODO | Dubbo 调用失败即丢失 |
| P1-2 | 大文件 OOM | `FileServiceImpl.java:66-75` | 4GB 文件需 4GB+ 内存 |
| P1-3 | String.intern() 同步 | `AccountServiceImpl` | String 池污染，性能下降 |
| P1-4 | 无熔断器 | 全局 | 级联故障风险 |

### 3.3 P2 - 运维问题 (1个月内修复)

| 编号 | 问题 | 位置 | 影响 |
|------|------|------|------|
| P2-1 | 软删除文件未清理 | `FileServiceImpl.java:134` TODO | 存储泄漏 |
| P2-2 | Bucket 缓存无失效 | `MinioMonitor.java:52` | 操作失败 |
| P2-3 | 无分布式追踪 | 全局 | 故障定位困难 |

---

## 四、演进路线图

### Phase 0: 紧急安全修复 (本周)

```
优先级: ████████████ 最高
预计工时: 8-12小时
```

#### 0.1 密钥泄露处理

```bash
# 1. 从 Git 历史中彻底移除私钥
git filter-repo --path platform-fisco/src/main/resources/conf/sdk.key --invert-paths

# 2. 轮换所有已暴露凭证
- FISCO 私钥: 重新生成并部署
- JWT 密钥: 生成新的强随机密钥
- 数据库密码: 更换并迁移至 Nacos 加密配置
```

#### 0.2 ID 混淆算法重构

**当前问题** (`IdUtils.java:98-135`):
```java
// 确定性哈希，相同输入永远产生相同输出
String hash = DigestUtils.sha256Hex(internalId + salt);
```

**修复方案**:
```java
// 方案A: UUID + 映射表（推荐）
public String toExternalId(Long internalId) {
    String cached = redisTemplate.opsForValue().get(INTERNAL_TO_EXTERNAL + internalId);
    if (cached != null) return cached;

    String externalId = UUID.randomUUID().toString().replace("-", "");
    redisTemplate.opsForValue().set(INTERNAL_TO_EXTERNAL + internalId, externalId);
    redisTemplate.opsForValue().set(EXTERNAL_TO_INTERNAL + externalId, internalId);
    return externalId;
}

// 方案B: HMAC + 时间戳（可轮换）
public String toExternalId(Long internalId) {
    long timestamp = System.currentTimeMillis() / 86400000; // 按天轮换
    String input = internalId + ":" + timestamp;
    return HmacUtils.hmacSha256Hex(secretKey, input).substring(0, 16);
}
```

#### 0.3 CORS 配置收紧

```yaml
# application.yml
spring:
  web:
    cors:
      allowed-origins:
        - https://your-frontend-domain.com
      allowed-methods: GET,POST,PUT,DELETE
      allowed-headers: Authorization,Content-Type
```

---

### Phase 1: 容错与弹性 (第2-3周)

```
优先级: ████████░░░░ 高
预计工时: 20-30小时
```

#### 1.1 引入 Resilience4j

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.2.0</version>
</dependency>
```

**熔断器配置**:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      fiscoService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
      minioService:
        slidingWindowSize: 20
        failureRateThreshold: 60
        waitDurationInOpenState: 20s
  retry:
    instances:
      dubboRetry:
        maxAttempts: 3
        waitDuration: 500ms
        retryExceptions:
          - java.io.IOException
          - org.apache.dubbo.rpc.RpcException
```

**应用到 FileServiceImpl**:
```java
@CircuitBreaker(name = "fiscoService", fallbackMethod = "storeFileFallback")
@Retry(name = "dubboRetry")
public File storeFile(Long userId, String fileName, ...) {
    // 现有逻辑
}

private File storeFileFallback(Long userId, String fileName, ..., Exception e) {
    log.error("区块链存证失败，进入降级流程", e);
    // 写入本地队列待重试
    pendingTaskQueue.offer(new PendingStoreTask(userId, fileName, ...));
    throw new GeneralException(ResultEnum.SERVICE_DEGRADED);
}
```

#### 1.2 流式文件处理

**问题代码** (`FileServiceImpl.java:66-75`):
```java
// 所有分片一次性加载到内存
List<byte[]> fileByteList = fileList.stream()
    .map(file -> Files.readAllBytes(file.toPath()))
    .toList();
```

**重构方案**:
```java
public File storeFile(Long userId, String fileName, List<java.io.File> fileList, ...) {
    List<String> storedPaths = new ArrayList<>();

    for (int i = 0; i < fileList.size(); i++) {
        java.io.File chunk = fileList.get(i);
        String hash = fileHashList.get(i);

        // 流式上传单个分片
        try (InputStream is = new BufferedInputStream(new FileInputStream(chunk))) {
            Result<String> result = storageService.storeFileStream(is, chunk.length(), hash);
            storedPaths.add(ResultUtils.getData(result));
        }
    }
    // 后续逻辑...
}
```

#### 1.3 同步锁重构

**问题代码** (`AccountServiceImpl.java`):
```java
synchronized (address.intern()) {  // 反模式
    // ...
}
```

**修复方案**:
```java
private final ConcurrentHashMap<String, ReentrantLock> addressLocks = new ConcurrentHashMap<>();

public void registerEmailVerifyCode(String address, ...) {
    ReentrantLock lock = addressLocks.computeIfAbsent(address, k -> new ReentrantLock());
    lock.lock();
    try {
        // 业务逻辑
    } finally {
        lock.unlock();
        // 可选: 定期清理不再使用的锁
    }
}
```

---

### Phase 2: 可观测性建设 (第4-5周)

```
优先级: ███████░░░░░ 中高
预计工时: 15-20小时
```

#### 2.1 分布式追踪集成

```xml
<!-- 推荐 SkyWalking，与 Dubbo 生态契合 -->
<dependency>
    <groupId>org.apache.skywalking</groupId>
    <artifactId>apm-toolkit-trace</artifactId>
    <version>9.1.0</version>
</dependency>
```

#### 2.2 结构化日志

```xml
<!-- logback-spring.xml -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <customFields>{"service":"record-platform"}</customFields>
</encoder>
```

#### 2.3 健康检查标准化

```java
@Component
public class FiscoHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        try {
            // 检查 FISCO 节点连通性
            boolean connected = fiscoClient.isConnected();
            return connected ? Health.up().build()
                            : Health.down().withDetail("reason", "Node unreachable").build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
```

---

### Phase 3: 数据一致性 (第6-8周)

```
优先级: ██████░░░░░░ 中
预计工时: 25-35小时
```

#### 3.1 Saga 补偿事务

```
当前问题:
上传 → MinIO成功 → 区块链失败 → MinIO数据成为孤立数据

解决方案: 引入状态机
```

```java
public enum FileUploadState {
    PENDING,        // 初始状态
    STORING,        // MinIO 写入中
    STORED,         // MinIO 写入完成
    CHAINING,       // 区块链写入中
    COMPLETED,      // 全部完成
    ROLLBACK,       // 回滚中
    FAILED          // 最终失败
}

@Service
public class FileUploadSaga {

    @Transactional
    public void processUpload(FileUploadContext ctx) {
        try {
            ctx.setState(FileUploadState.STORING);
            storeToMinio(ctx);

            ctx.setState(FileUploadState.CHAINING);
            storeToBlockchain(ctx);

            ctx.setState(FileUploadState.COMPLETED);
        } catch (MinioException e) {
            ctx.setState(FileUploadState.FAILED);
            throw e;
        } catch (BlockchainException e) {
            ctx.setState(FileUploadState.ROLLBACK);
            compensateMinio(ctx);  // 回滚 MinIO
            ctx.setState(FileUploadState.FAILED);
            throw e;
        }
    }
}
```

#### 3.2 定时清理任务

```java
@Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨3点
public void purgeDeletedFiles() {
    // 1. 查询待清理文件
    List<File> toDelete = fileMapper.selectList(
        new LambdaQueryWrapper<File>()
            .eq(File::getDeleted, 1)
            .lt(File::getUpdateTime, LocalDateTime.now().minusDays(30))
    );

    for (File file : toDelete) {
        try {
            // 2. 删除 MinIO 对象
            storageService.deleteFile(file.getFileHash());
            // 3. 标记区块链记录作废
            blockChainService.markFileDeleted(file.getFileHash());
            // 4. 物理删除数据库记录
            fileMapper.deleteById(file.getId());
        } catch (Exception e) {
            log.error("清理文件失败: {}", file.getFileHash(), e);
        }
    }
}
```

---

### Phase 4: 性能优化 (第9-10周)

```
优先级: █████░░░░░░░ 中
预计工时: 15-20小时
```

#### 4.1 MinIO 智能路由

```java
// 从轮询改为延迟感知选择
public MinioClient selectOptimalClient() {
    return clientCache.values().stream()
        .filter(MinioClientWrapper::isHealthy)
        .min(Comparator.comparingLong(MinioClientWrapper::getAvgLatency))
        .map(MinioClientWrapper::getClient)
        .orElseThrow(() -> new GeneralException(ResultEnum.NO_AVAILABLE_STORAGE));
}
```

#### 4.2 缓存策略

```java
@Cacheable(value = "fileMetadata", key = "#fileHash", unless = "#result == null")
public File getFileMetadata(String fileHash) {
    return fileMapper.selectOne(
        new LambdaQueryWrapper<File>().eq(File::getFileHash, fileHash)
    );
}

@CacheEvict(value = "fileMetadata", key = "#fileHash")
public void updateFileMetadata(String fileHash, File file) {
    // 更新逻辑
}
```

#### 4.3 Bucket 缓存失效

```java
private final Cache<String, Boolean> bucketExistenceCache = Caffeine.newBuilder()
    .expireAfterWrite(30, TimeUnit.MINUTES)
    .maximumSize(1000)
    .build();

public boolean bucketExists(String bucketName) {
    return bucketExistenceCache.get(bucketName, k -> {
        try {
            return minioClient.bucketExists(BucketExistsArgs.builder().bucket(k).build());
        } catch (Exception e) {
            return false;
        }
    });
}
```

---

### Phase 5: 架构演进 (长期)

```
优先级: ████░░░░░░░░ 规划中
时间框架: Q2-Q3 2025
```

#### 5.1 事件驱动架构

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ 业务服务     │────►│  Outbox 表  │────►│ CDC/轮询    │
│ (写入事务)   │     │             │     │ 事件发布    │
└─────────────┘     └─────────────┘     └──────┬──────┘
                                               │
                    ┌──────────────────────────┼──────────────────────────┐
                    │                          │                          │
                    ▼                          ▼                          ▼
           ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
           │ MinIO Worker│            │ FISCO Worker│            │ 通知 Worker │
           │ (存储处理)   │            │ (区块链存证) │            │ (用户通知)  │
           └─────────────┘            └─────────────┘            └─────────────┘
```

#### 5.2 API 版本化

```java
@RestController
@RequestMapping("/api/v1/files")
public class FileControllerV1 { ... }

@RestController
@RequestMapping("/api/v2/files")
public class FileControllerV2 { ... }  // 新版本，支持流式上传
```

#### 5.3 多租户支持

```java
@Schema(description = "租户ID")
@TableField("tenant_id")
private Long tenantId;

// 自动填充租户上下文
@Component
public class TenantMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.setFieldValByName("tenantId", TenantContext.getCurrentTenantId(), metaObject);
    }
}
```

---

## 五、优先级矩阵

```
紧急度 ↑
    │
    │  ┌─────────────┐     ┌─────────────┐
    │  │  Phase 0    │     │  Phase 1    │
    │  │ 安全修复    │     │ 容错弹性    │
    │  │ P0-1~P0-4   │     │ P1-1~P1-4   │
    │  └─────────────┘     └─────────────┘
    │
    │  ┌─────────────┐     ┌─────────────┐
    │  │  Phase 2    │     │  Phase 3    │
    │  │ 可观测性    │     │ 数据一致性  │
    │  │ P2-3        │     │ P2-1,P2-2   │
    │  └─────────────┘     └─────────────┘
    │
    │  ┌─────────────┐     ┌─────────────┐
    │  │  Phase 4    │     │  Phase 5    │
    │  │ 性能优化    │     │ 架构演进    │
    │  └─────────────┘     └─────────────┘
    │
    └─────────────────────────────────────────► 重要度
```

---

## 六、技术选型建议

| 领域 | 推荐方案 | 理由 |
|------|----------|------|
| 熔断限流 | Resilience4j | 轻量级，与 Spring Boot 3 集成好 |
| 分布式追踪 | SkyWalking | 对 Dubbo 原生支持，Agent 无侵入 |
| 密钥管理 | Nacos 加密配置 | 复用现有基础设施 |
| 本地缓存 | Caffeine | 高性能，支持 TTL |
| 消息队列 | RabbitMQ (已有) | 复用现有基础设施 |

---

## 七、风险提示

1. **Phase 0 必须本周完成** - 私钥泄露是 P0 安全事故，每拖延一天风险增加
2. **ID 混淆重构需数据迁移** - 需要同时维护新旧映射，制定迁移计划
3. **流式处理改造影响接口契约** - 需要评估前端和 Dubbo 接口兼容性
4. **Saga 模式增加复杂度** - 建议先用简单的重试队列，再考虑完整 Saga

---

## 八、下一步行动

### 本周 (Week 1)
- [ ] 执行 Git 历史清理，移除 sdk.key
- [ ] 轮换所有已暴露密钥
- [ ] 实现 ID 混淆算法 v2
- [ ] 收紧 CORS 配置

### 下周 (Week 2)
- [ ] 引入 Resilience4j
- [ ] 实现 Dubbo 调用重试
- [ ] 修复 String.intern() 同步问题

### 第三周 (Week 3)
- [ ] 流式文件处理重构
- [ ] 熔断器配置与测试

---

## 九、附录

### A. 关键文件索引

| 文件 | 行号 | 问题/改动 |
|------|------|----------|
| `IdUtils.java` | 60-117 | ID 混淆算法 (待重构) |
| `FileServiceImpl.java` | 61, 66-75, 134 | 重试TODO, OOM风险, 清理TODO |
| `AccountServiceImpl.java` | - | String.intern() 同步 (待修复) |
| `OperationLogAspect.java` | 42, 268-279 | 已修复: 覆盖/api/file |
| `Convert.java` | 15-17 | 已修复: DateTimeFormatter |
| `JwtAuthenticationFilter.java` | 48-64 | 已修复: 常量使用 |

### B. 参考资源

- [Resilience4j 官方文档](https://resilience4j.readme.io/)
- [SkyWalking Dubbo 插件](https://skywalking.apache.org/docs/)
- [FISCO BCOS 密钥管理最佳实践](https://fisco-bcos-documentation.readthedocs.io/)
