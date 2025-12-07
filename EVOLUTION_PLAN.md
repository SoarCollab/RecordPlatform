# RecordPlatform 系统演进规划 v4.0

> 更新日期: 2025-12-07
> 基于代码全面深度分析

---

## 一、现状评估

### 1.1 模块评分总览

| 模块 | 评分 | 核心优势 | 主要短板 |
|------|------|----------|----------|
| **backend** | 7.1/10 | Saga+Outbox架构先进、多租户完整 | MDC传递问题、测试覆盖不足 |
| **fisco** | 5.6/10 | 功能完整、Dubbo集成 | 合约设计缺陷、返回值解析脆弱 |
| **minio** | 6.5/10 | 2副本冗余、负载均衡 | 配置变更一致性、副本修复缺失 |
| **api** | 6.5/10 | 统一Result封装、接口清晰 | 无版本控制、DTO不完整 |

**整体成熟度**：`6.5/10` - 基础架构完善，生产环境需强化可靠性和可观测性

### 1.2 已完成的基础建设

| 维度 | 状态 | 说明 |
|------|------|------|
| **安全性** | 8/10 | JWT无默认密钥、ID混淆(UUID+Redis)、CORS白名单、限流 |
| **容错能力** | 8/10 | Resilience4j熔断+重试、Saga补偿事务、Outbox可靠消息 |
| **可观测性** | 6/10 | MDC传播+结构化日志完成，SkyWalking待完善 |
| **数据一致性** | 8/10 | FileSagaOrchestrator + OutboxPublisher实现 |
| **架构设计** | 8/10 | 分层清晰，Dubbo Triple协议 |

### 1.3 核心技术栈

- Java 21 + Spring Boot 3.2.11 + Dubbo 3.3.3 (Triple)
- FISCO BCOS 3.8.0 + Solidity ^0.8.11
- MinIO 8.5.9 (2副本) + Nacos动态配置
- MySQL + MyBatis-Plus + Redis + RabbitMQ

---

## 二、演进路线图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          演进路线时序图                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  P0 (立即)     P1 (2周)      P2 (1月)      P3 (2月)      P4 (长期)      │
│     │            │             │             │             │            │
│     ▼            ▼             ▼             ▼             ▼            │
│  ┌──────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐     │
│  │ 稳定性 │   │ 可观测性 │   │ 多租户  │   │ API版本 │   │ 架构升级│     │
│  │ 修复  │   │ 增强    │   │ 完善    │   │ 化      │   │         │     │
│  └──────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘     │
│                                                                         │
│  • MDC传递     • SkyWalking   • 存储路径     • v1/v2接口   • 事件溯源   │
│  • 分布式锁    • 结构化日志    • Redis隔离    • 契约测试    • CQRS分离   │
│  • 副本修复    • 健康检查完善  • Dubbo传播    • 灰度发布    • 虚拟线程   │
│  • 熔断优化    • Prometheus   • 租户管理     • OpenAPI    • 区块链HA   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、P0：稳定性修复（立即执行）

### 3.1 MDC 异步传递问题

**问题**：`TenantContext` 和 `traceId` 在异步任务中丢失

**影响文件**：
- `backend-web/src/main/java/cn/flying/filter/JwtAuthenticationFilter.java:89-95`
- `backend-service/src/main/java/cn/flying/service/listener/FileEventRabbitListener.java:97-109`

**解决方案**：新增 `AsyncConfiguration.java`
```java
@Configuration
@EnableAsync
public class AsyncConfiguration {
    @Bean
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setTaskDecorator(runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            Long tenantId = TenantContext.getTenantId();
            return () -> {
                try {
                    if (context != null) MDC.setContextMap(context);
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    runnable.run();
                } finally {
                    MDC.clear();
                    TenantContext.clear();
                }
            };
        });
        return executor;
    }
}
```

### 3.2 定时任务分布式锁

**问题**：`FileCleanupTask`、`ProcessedMessageCleanupTask` 多实例重复执行

**影响文件**：
- `backend-service/src/main/java/cn/flying/service/job/FileCleanupTask.java`
- `backend-service/src/main/java/cn/flying/service/job/ProcessedMessageCleanupTask.java`

**解决方案**：集成 Redisson 分布式锁
```java
@Scheduled(cron = "${file.cleanup.cron}")
public void cleanDeletedFiles() {
    RLock lock = redissonClient.getLock("file:cleanup:lock");
    if (lock.tryLock(0, 3600, TimeUnit.SECONDS)) {
        try { performCleanup(); }
        finally { lock.unlock(); }
    }
}
```

### 3.3 MinIO 副本一致性补偿

**问题**：上传一成一失导致单副本风险

**影响文件**：
- `platform-minio/src/main/java/cn/flying/minio/service/DistributedStorageServiceImpl.java:156`

**解决方案**：新增 `ConsistencyRepairService.java`
```java
@Scheduled(fixedRate = 300000) // 5分钟
public void repairInconsistentReplicas() {
    List<InconsistentRecord> records = findInconsistentRecords();
    for (var record : records) {
        copyToMissingNode(record.fileHash, record.presentNode, record.missingNode);
        markRepaired(record.id);
    }
}
```

### 3.4 Saga 补偿指数退避

**问题**：固定3次重试，网络波动易失败

**影响文件**：
- `backend-service/src/main/java/cn/flying/service/saga/FileSagaOrchestrator.java:177`

**改进**：
```java
private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600, 3600};

private void scheduleRetry(FileSaga saga) {
    int index = Math.min(saga.getRetryCount(), BACKOFF_SECONDS.length - 1);
    saga.setNextRetryAt(Instant.now().plusSeconds(BACKOFF_SECONDS[index]));
    saga.setStatus(FileSagaStatus.PENDING_COMPENSATION);
}
```

---

## 四、P1：可观测性增强（2周内）

### 4.1 SkyWalking 完整集成

**当前状态**：已添加依赖，需完善 Agent 配置

**实施步骤**：
```bash
# 启动脚本添加
java -javaagent:skywalking-agent.jar \
     -Dskywalking.agent.service_name=backend-web \
     -Dskywalking.collector.backend_service=oap:11800 \
     -jar backend-web.jar
```

**涉及文件**：
- `scripts/skywalking-env.sh` (已存在)
- `scripts/start-with-skywalking.sh` (已存在)
- `platform-fisco/scripts/` (需新增)
- `platform-minio/scripts/` (需新增)

### 4.2 健康检查完善

**现有指标**：Database、MinIO、RabbitMQ、Redis、FISCO

**缺失指标**：

| 指标 | 实现位置 | 说明 |
|------|----------|------|
| Saga 积压 | backend-service | 添加 `SagaHealthIndicator` |
| Outbox 积压 | backend-service | 添加 `OutboxHealthIndicator` |
| 熔断器状态 | backend-service | 暴露 Resilience4j actuator |

**示例实现**：
```java
@Component("saga")
public class SagaHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        long pendingCount = sagaMapper.countByStatus("RUNNING");
        long failedCount = sagaMapper.countByStatus("FAILED");

        if (failedCount > 10) return Health.down()
            .withDetail("failed", failedCount).build();
        if (pendingCount > 100) return Health.status("DEGRADED")
            .withDetail("pending", pendingCount).build();
        return Health.up().build();
    }
}
```

### 4.3 结构化日志标准化

**问题**：日志语言混用（中英文）、格式不统一

**涉及文件**：
- `backend-web/src/main/resources/logback-spring.xml`

**标准化配置**：
```xml
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <customFields>{"service":"backend-web","env":"${ENV}"}</customFields>
    <fieldNames>
        <timestamp>@timestamp</timestamp>
        <message>msg</message>
    </fieldNames>
</encoder>
```

---

## 五、P2：多租户完善（1个月内）

### 5.1 存储路径隔离

**当前**：`minio/node/{logicNode}/{objectName}`

**改进**：`minio/tenant/{tenantId}/node/{logicNode}/{objectName}`

**涉及文件**：
- `platform-minio/src/main/java/cn/flying/minio/service/DistributedStorageServiceImpl.java:245`
- `backend-service/src/main/java/cn/flying/service/impl/FileServiceImpl.java`

### 5.2 Redis Key 隔离

**当前**：`jwt:blacklist:{jwtId}`

**改进**：`tenant:{tenantId}:jwt:blacklist:{jwtId}`

**涉及文件**：
- `backend-common/src/main/java/cn/flying/common/util/JwtUtils.java:67`
- `backend-common/src/main/java/cn/flying/common/util/Const.java`

### 5.3 Dubbo 租户上下文传播

**问题**：Dubbo 调用不自动传播 tenantId

**涉及文件**：
- `backend-service/src/main/java/cn/flying/service/filter/MdcDubboFilter.java`

**解决方案**：
```java
@Override
public Result invoke(Invoker<?> invoker, Invocation invocation) {
    // 消费者端：设置 attachment
    Long tenantId = TenantContext.getTenantId();
    if (tenantId != null) {
        RpcContext.getContext().setAttachment("tenantId", tenantId.toString());
    }
    // 提供者端：从 attachment 恢复
    String tenantIdStr = RpcContext.getContext().getAttachment("tenantId");
    if (tenantIdStr != null) {
        TenantContext.setTenantId(Long.parseLong(tenantIdStr));
    }
    try {
        return invoker.invoke(invocation);
    } finally {
        TenantContext.clear();
    }
}
```

---

## 六、P3：API版本化（2个月内）

### 6.1 接口版本策略

```
当前：/record-platform/file/list
v1：  /record-platform/api/v1/file/list  (冻结)
v2：  /record-platform/api/v2/file/list  (新功能)
```

### 6.2 Dubbo 服务版本

**涉及文件**：
- `platform-api/src/main/java/cn/flying/platformapi/external/BlockChainService.java`
- `platform-api/src/main/java/cn/flying/platformapi/external/DistributedStorageService.java`

```java
// platform-api 冻结 v1
@DubboService(version = "1.0.0")
public interface BlockChainService { ... }

// 新建 v2 接口
@DubboService(version = "2.0.0")
public interface BlockChainServiceV2 {
    // 流式上传、分页查询等新功能
}
```

### 6.3 DTO 补全

**问题**：`FileDetailVO` 缺失关键字段

**涉及文件**：
- `platform-api/src/main/java/cn/flying/platformapi/response/FileDetailVO.java`

**改进**：
```java
public class FileDetailVO {
    private String fileHash;      // 当前被注释，需恢复
    private Long fileSize;        // 新增
    private String mimeType;      // 新增
    private Long uploadTimestamp; // 替代 String uploadTime
}
```

### 6.4 契约测试

**工具选择**：Spring Cloud Contract 或 Pact

**实施**：
- 在 CI 中加入契约测试
- 生成 OpenAPI 文档
- 支持灰度发布

---

## 七、P4：长期架构演进

### 7.1 区块链高可用

**当前问题**：单节点 FISCO Peer 是 SPOF

**涉及文件**：
- `platform-fisco/src/main/resources/application.yml`
- `platform-fisco/src/main/java/cn/flying/fisco_bcos/config/SdkBeanConfig.java`

**解决方案**：
```yaml
bcos:
  network:
    peers:
      - 127.0.0.1:20200
      - 127.0.0.2:20200
      - 127.0.0.3:20200
```

### 7.2 智能合约优化

**当前问题**：
1. 文件内容直接存储在链上，极其浪费
2. 分享码随机数生成不安全
3. 缺少事件发射

**涉及文件**：
- `platform-fisco/src/main/contracts/Storage.sol`
- `platform-fisco/src/main/contracts/Sharing.sol`

**改进方向**：
```solidity
// 1. 仅存储哈希和元数据
struct File {
    string fileName;
    string uploader;
    bytes32 contentHash;  // 仅存哈希，内容由 MinIO 存储
    string param;
    uint256 uploadTime;
}

// 2. 添加事件发射
event FileStored(bytes32 indexed fileHash, string uploader, uint256 timestamp);
event FileDeleted(bytes32 indexed fileHash, string uploader, uint256 timestamp);

// 3. 修复分享码生成安全问题
```

### 7.3 CQRS 读写分离

```
┌─────────────────────────────────────────────────────────────────┐
│                         CQRS 架构演进                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Command Side                    Query Side                    │
│   ┌─────────────┐                ┌─────────────┐               │
│   │ FileService │                │ FileQuery   │               │
│   │ (写入)      │                │ (只读)      │               │
│   └──────┬──────┘                └──────┬──────┘               │
│          │                              │                       │
│          ▼                              ▼                       │
│   ┌─────────────┐                ┌─────────────┐               │
│   │ MySQL       │───────────────▶│ Redis/ES    │               │
│   │ (主库)      │   CDC/Event    │ (读库)      │               │
│   └─────────────┘                └─────────────┘               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**实施阶段**：
1. 当前: Outbox + 消费者 (已完成)
2. 中期: 添加物化视图服务，消费事件重建查询模型
3. 长期: 完整 CQRS，读写库分离

### 7.4 Java 21 虚拟线程

```java
// 替换传统线程池
@Bean
public Executor virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```

---

## 八、关键行动项汇总

| 优先级 | 任务 | 预估工时 | 涉及模块 |
|--------|------|----------|----------|
| **P0-1** | MDC/TenantContext 异步传递 | 2天 | backend-web |
| **P0-2** | 定时任务分布式锁 | 1天 | backend-service |
| **P0-3** | MinIO 副本一致性补偿 | 3天 | platform-minio |
| **P0-4** | Saga 指数退避重试 | 1天 | backend-service |
| **P1-1** | SkyWalking Agent 全面部署 | 2天 | 全部 |
| **P1-2** | 健康检查指标补全 | 2天 | backend-web |
| **P1-3** | 日志规范化 | 1天 | 全部 |
| **P2-1** | 存储路径租户隔离 | 3天 | minio, backend |
| **P2-2** | Dubbo 租户传播 | 2天 | backend-service |
| **P3-1** | API 版本化框架 | 5天 | api, backend |
| **P3-2** | DTO 字段补全 | 2天 | api |
| **P4-1** | 区块链多节点 | 5天 | platform-fisco |
| **P4-2** | 合约优化（内容下链） | 10天 | platform-fisco |

---

## 九、技术债务清单

| 债务项 | 严重度 | 位置 | 建议 |
|--------|--------|------|------|
| 测试覆盖率 <10% | 高 | 全部模块 | 补充核心路径单测 |
| FileDetailVO 缺 fileHash | 高 | api/response | 恢复注释字段 |
| 智能合约内容上链 | 高 | Storage.sol | 迁移到 MinIO |
| 返回值魔数索引 | 中 | BlockChainServiceImpl | 使用 Map/VO |
| 日志语言混用 | 中 | 全部 | 统一中文 |
| ResultEnum 编码混乱 | 中 | api/constant | 重新规划分段 |
| Caffeine 无预热 | 低 | backend-web | 启动时预热 |
| OkHttpClient 未关闭 | 低 | MinioMonitor | PreDestroy 清理 |

---

## 十、关键文件索引

| 模块 | 关键文件 | 改动类型 |
|------|----------|----------|
| **P0-稳定性** | `JwtAuthenticationFilter.java`, `FileCleanupTask.java` | 修改 |
| **P0-稳定性** | `AsyncConfiguration.java`, `ConsistencyRepairService.java` | 新增 |
| **P1-追踪** | `MdcDubboFilter.java`, `logback-spring.xml` | 增强 |
| **P1-健康** | `SagaHealthIndicator.java`, `OutboxHealthIndicator.java` | 新增 |
| **P2-多租户** | `DistributedStorageServiceImpl.java`, `JwtUtils.java` | 修改 |
| **P3-API** | `BlockChainService.java`, `FileDetailVO.java` | 修改 |
| **P4-区块链** | `Storage.sol`, `Sharing.sol`, `SdkBeanConfig.java` | 修改 |

---

## 十一、模块详细分析摘要

### 11.1 platform-backend (7.1/10)

**架构设计**：8.5/10 - Saga + Outbox 模式先进
**安全性**：7/10 - JWT + RBAC 完善
**可维护性**：7.5/10 - 代码结构清晰
**测试覆盖**：4/10 - 严重不足

**关键问题**：
1. MDC ThreadLocal 在异步任务中丢失
2. 多实例部署下定时任务重复执行
3. Saga 补偿重试次数有限
4. 缓存雪崩风险

### 11.2 platform-fisco (5.6/10)

**代码质量**：65/100 - 基础功能完整
**架构设计**：60/100 - 合约设计存在根本性缺陷
**错误处理**：55/100 - 缺乏统一的异常映射
**测试覆盖**：40/100 - 仅有密钥生成测试

**关键问题**：
1. 文件内容直接存储在链上，极其浪费
2. 返回值解析使用魔数索引，维护困难
3. 私钥从配置文件明文读取
4. FileDetailVO 缺少 fileHash 字段

### 11.3 platform-minio (6.5/10)

**架构清晰度**：8/10 - 分层明确
**并发控制**：7/10 - 使用 ConcurrentHashMap + CompletableFuture
**故障转移**：7/10 - 实现了主备降级
**测试覆盖**：2/10 - 无单元测试

**关键问题**：
1. 配置变更时的路由不一致风险
2. 上传一成一失导致单副本
3. Prometheus 指标采集单点
4. 日志语言混用

### 11.4 platform-api (6.5/10)

**设计合理性**：6.5/10 - 接口清晰但缺乏版本控制
**扩展性**：5.5/10 - 无 API 版本协商机制

**关键问题**：
1. 无版本控制机制
2. DTO 字段设计不规范
3. transactionHash 职责混乱
4. 参数验证责任不清

---

## 十二、总结

**系统演进的核心方向**：

1. **稳定性优先**：修复 P0 级问题，确保分布式场景下的数据一致性
2. **可观测性增强**：完善 SkyWalking 链路追踪，实现全链路问题定位
3. **多租户深化**：从数据库隔离扩展到存储、缓存、消息队列全面隔离
4. **API 演进**：引入版本化机制，支持平滑升级和灰度发布
5. **架构升级**：长期向 CQRS、事件溯源、虚拟线程等现代架构演进

项目整体架构设计合理，Saga + Outbox 模式是业界最佳实践。当前的核心工作应聚焦于**稳定性修复**和**可观测性增强**，为后续的多租户商业化和高可用部署奠定基础。
