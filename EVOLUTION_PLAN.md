# RecordPlatform 系统演进规划 v6.0

> 更新日期: 2025-12-11
> 聚焦 P4 长期架构升级

---

## 一、现状总结

### 1.1 已完成事项 ✅

| 阶段 | 完成内容 |
|------|----------|
| **P0** | encodeCid Bug、SecureIdAspect 异常处理、CORS 通配符禁用、大文件上传超时优化 |
| **P1** | HTTPS 强制配置、分布式流控改造、MDC/TenantContext 异步传递、定时任务分布式锁、Saga 补偿原子化 |
| **P2** | SkyWalking Agent 全面部署、健康检查指标补全、结构化日志标准化 |
| **P3** | 存储路径租户隔离、Redis Key 租户隔离、Dubbo 租户传播 |

**系统整体成熟度**：`8.0/10` - 基础架构完善，生产环境就绪，进入长期优化阶段

### 1.2 核心技术栈

- Java 21 + Spring Boot 3.2.11 + Dubbo 3.3.3 (Triple)
- FISCO BCOS 3.8.0 + Solidity ^0.8.11
- MinIO 8.5.9 (2副本) + Nacos动态配置
- MySQL + MyBatis-Plus + Redis + RabbitMQ

---

## 二、P4 架构升级总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        P4 架构升级路线图                                 │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│    P4-1              P4-2              P4-3              P4-4           │
│  API版本化         区块链HA         智能合约优化        CQRS/虚拟线程   │
│                                                                         │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐      ┌─────────┐       │
│  │REST v1/v2│      │多节点配置│      │内容下链  │      │读写分离  │       │
│  │Dubbo版本 │      │故障转移  │      │事件优化  │      │物化视图  │       │
│  │契约测试  │      │健康检查  │      │安全加固  │      │虚拟线程  │       │
│  └─────────┘      └─────────┘      └─────────┘      └─────────┘       │
│                                                                         │
│    10h               10h               20h               15h            │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、P4-1：API 版本化框架（预估 10h）

### 3.1 现状分析

**当前状态**：
- REST API 已采用 `/api/v1/` 前缀
- Dubbo 服务定义了 `VERSION = "2.0.0"` 常量
- 无多版本并行运行能力
- 无 API 文档版本隔离

**涉及文件**：
```
platform-api/src/main/java/cn/flying/platformapi/external/BlockChainService.java
platform-api/src/main/java/cn/flying/platformapi/external/DistributedStorageService.java
platform-api/src/main/java/cn/flying/platformapi/response/FileDetailVO.java
platform-backend/backend-web/src/main/java/cn/flying/controller/FileController.java
```

### 3.2 REST API 版本化方案

#### 3.2.1 版本路由策略

```
当前：/api/v1/files/list
v2：  /api/v2/files/list  (新功能、破坏性变更)
```

#### 3.2.2 Controller 结构重组

**方案一：继承策略（推荐）**

```java
// 1. 抽象基类定义通用逻辑
@RestController
public abstract class AbstractFileController {
    @Autowired
    protected FileService fileService;

    protected Result<PageInfo<FileVO>> doListFiles(Long userId, int page, int size) {
        return fileService.listFiles(userId, page, size);
    }
}

// 2. V1 Controller（冻结，仅维护）
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "File API v1", description = "文件管理 API v1 (稳定版)")
public class FileControllerV1 extends AbstractFileController {

    @GetMapping("/list")
    @Operation(summary = "获取文件列表", deprecated = true)
    public Result<PageInfo<FileVO>> listFiles(
            @RequestParam @Min(1) int page,
            @RequestParam @Min(1) @Max(100) int size) {
        return doListFiles(getCurrentUserId(), page, size);
    }
}

// 3. V2 Controller（新功能）
@RestController
@RequestMapping("/api/v2/files")
@Tag(name = "File API v2", description = "文件管理 API v2 (当前版本)")
public class FileControllerV2 extends AbstractFileController {

    @GetMapping("/list")
    @Operation(summary = "获取文件列表 (支持游标分页)")
    public Result<CursorPage<FileVO>> listFiles(
            @RequestParam(required = false) String cursor,  // 新增游标分页
            @RequestParam @Min(1) @Max(100) int size,
            @RequestParam(required = false) String sortBy,   // 新增排序
            @RequestParam(defaultValue = "desc") String order) {
        return doListFilesV2(getCurrentUserId(), cursor, size, sortBy, order);
    }

    // V2 新增：批量操作
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除文件")
    public Result<BatchDeleteResult> batchDelete(@RequestBody @Valid BatchDeleteRequest request) {
        return fileService.batchDelete(request);
    }
}
```

**方案二：版本路由装饰器**

```java
// 新增：ApiVersion 注解
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiVersion {
    int[] value();  // 支持多版本
}

// 版本路由映射器
@Component
public class ApiVersionRequestMappingHandlerMapping extends RequestMappingHandlerMapping {
    @Override
    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
        RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
        if (info == null) return null;

        ApiVersion version = AnnotatedElementUtils.findMergedAnnotation(method, ApiVersion.class);
        if (version == null) {
            version = AnnotatedElementUtils.findMergedAnnotation(handlerType, ApiVersion.class);
        }
        if (version != null) {
            return createVersionInfo(info, version.value());
        }
        return info;
    }
}
```

#### 3.2.3 Swagger 文档版本隔离

```java
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
                .group("v1")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(openApi ->
                    openApi.info(new Info()
                        .title("RecordPlatform API v1")
                        .version("1.0.0")
                        .description("稳定版本 - 仅维护性更新")))
                .build();
    }

    @Bean
    public GroupedOpenApi v2Api() {
        return GroupedOpenApi.builder()
                .group("v2")
                .pathsToMatch("/api/v2/**")
                .addOpenApiCustomizer(openApi ->
                    openApi.info(new Info()
                        .title("RecordPlatform API v2")
                        .version("2.0.0")
                        .description("当前版本 - 持续迭代")))
                .build();
    }
}
```

### 3.3 Dubbo 服务版本化方案

#### 3.3.1 接口版本策略

```java
// platform-api: 冻结 v1.0.0 接口
public interface BlockChainService {
    String VERSION_V1 = "1.0.0";  // 旧版兼容
    String VERSION_V2 = "2.0.0";  // 当前版本

    // V1 方法签名（已废弃，仅保留兼容）
    @Deprecated
    Result<Boolean> delete(String fileHash, String uploader, String param);

    // V2 方法签名（推荐）
    Result<BatchDeleteResponse> batchDelete(BatchDeleteRequest request);
}

// platform-fisco: 提供双版本实现
@DubboService(version = BlockChainService.VERSION_V1)
public class BlockChainServiceImplV1 implements BlockChainService {
    // V1 实现：内部调用 V2 方法后适配返回
}

@DubboService(version = BlockChainService.VERSION_V2)
public class BlockChainServiceImplV2 implements BlockChainService {
    // V2 实现：原生新逻辑
}

// platform-backend: 消费端选择版本
@DubboReference(version = BlockChainService.VERSION_V2)
private BlockChainService blockChainService;
```

#### 3.3.2 版本协商机制

```yaml
# application.yml
dubbo:
  consumer:
    version: 2.0.0
    fallback:
      enabled: true
      versions: 1.0.0  # V2 不可用时降级到 V1
```

### 3.4 DTO 补全方案

```java
// FileDetailVO 增强
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDetailVO implements Serializable {
    private String fileName;
    private String uploader;
    private String param;
    private String content;
    private String fileHash;           // ✅ 已存在
    private Long fileSize;             // 新增：文件大小（字节）
    private String mimeType;           // 新增：MIME 类型
    private Long uploadTimestamp;      // ✅ 已存在（毫秒时间戳）
    private String uploadTime;         // 格式化时间字符串

    // V2 新增字段
    private Integer version;           // 文件版本号
    private String checksumAlgorithm;  // 校验算法（SHA-256）
    private Map<String, String> metadata; // 扩展元数据
}
```

### 3.5 契约测试方案

**工具选择**：Spring Cloud Contract

```xml
<!-- platform-api/pom.xml -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-contract-verifier</artifactId>
    <scope>test</scope>
</dependency>
```

**契约定义示例**：

```groovy
// src/test/resources/contracts/file/getFile.groovy
Contract.make {
    description "Should return file details by hash"
    request {
        method GET()
        url "/api/v2/files/0x123abc"
        headers {
            header("Authorization", "Bearer token")
        }
    }
    response {
        status 200
        body([
            code: 200,
            message: "success",
            data: [
                fileName: anyNonBlankString(),
                fileHash: "0x123abc",
                uploadTimestamp: anyNumber()
            ]
        ])
    }
}
```

### 3.6 实施步骤

| 步骤 | 任务 | 工时 |
|------|------|------|
| 1 | 创建 AbstractFileController 基类 | 1h |
| 2 | 拆分 V1/V2 Controller | 2h |
| 3 | 配置 Swagger 版本分组 | 1h |
| 4 | Dubbo 接口添加版本常量 | 1h |
| 5 | 实现双版本 Dubbo Service | 2h |
| 6 | DTO 字段补全 | 1h |
| 7 | 编写契约测试 | 2h |

---

## 四、P4-2：区块链高可用（预估 10h）

### 4.1 现状分析

**当前问题**：
- 单节点配置：`peers[0]: 127.0.0.1:20200`
- 无故障检测和自动重连
- 无负载均衡
- 节点宕机时系统不可用

**涉及文件**：
```
platform-fisco/src/main/resources/application.yml
platform-fisco/src/main/java/cn/flying/fisco_bcos/config/SdkBeanConfig.java
platform-fisco/src/main/java/cn/flying/fisco_bcos/config/BcosConfig.java
```

### 4.2 多节点配置方案

#### 4.2.1 配置文件改造

```yaml
# application.yml
bcos:
  network:
    peers:
      - 192.168.1.101:20200  # 节点1
      - 192.168.1.102:20200  # 节点2
      - 192.168.1.103:20200  # 节点3

  # 新增：连接池配置
  connection:
    pool-size: 10
    connect-timeout: 5000
    read-timeout: 30000
    retry-times: 3
    retry-interval: 1000

  # 新增：健康检查配置
  health-check:
    enabled: true
    interval: 30000
    timeout: 5000
```

#### 4.2.2 配置类改造

```java
@Data
@ConfigurationProperties(prefix = "bcos")
public class BcosConfig {
    private Map<String, Object> cryptoMaterial;
    private Map<String, List<String>> network;

    // 新增：连接配置
    private ConnectionConfig connection = new ConnectionConfig();
    private HealthCheckConfig healthCheck = new HealthCheckConfig();

    @Data
    public static class ConnectionConfig {
        private int poolSize = 10;
        private int connectTimeout = 5000;
        private int readTimeout = 30000;
        private int retryTimes = 3;
        private int retryInterval = 1000;
    }

    @Data
    public static class HealthCheckConfig {
        private boolean enabled = true;
        private int interval = 30000;
        private int timeout = 5000;
    }
}
```

### 4.3 客户端池化与故障转移

#### 4.3.1 ClientPool 实现

```java
@Component
@Slf4j
public class FiscoClientPool implements DisposableBean {

    private final List<ClientWrapper> clients = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private final BcosConfig bcosConfig;
    private final ScheduledExecutorService healthChecker;

    @Data
    @AllArgsConstructor
    public static class ClientWrapper {
        private final String peer;
        private final Client client;
        private volatile boolean healthy = true;
        private volatile long lastHealthCheck = System.currentTimeMillis();
    }

    @PostConstruct
    public void init() {
        List<String> peers = bcosConfig.getNetwork().get("peers");
        for (String peer : peers) {
            try {
                Client client = createClient(peer);
                clients.add(new ClientWrapper(peer, client, true, System.currentTimeMillis()));
                log.info("FISCO client initialized for peer: {}", peer);
            } catch (Exception e) {
                log.warn("Failed to initialize client for peer: {}", peer, e);
            }
        }

        if (clients.isEmpty()) {
            throw new IllegalStateException("No FISCO clients available");
        }

        // 启动健康检查
        if (bcosConfig.getHealthCheck().isEnabled()) {
            startHealthCheck();
        }
    }

    /**
     * 获取健康客户端（Round-Robin + 故障跳过）
     */
    public Client getClient() {
        int attempts = clients.size();
        for (int i = 0; i < attempts; i++) {
            int index = roundRobinIndex.getAndIncrement() % clients.size();
            ClientWrapper wrapper = clients.get(index);
            if (wrapper.isHealthy()) {
                return wrapper.getClient();
            }
        }
        throw new FiscoClientException("No healthy FISCO client available");
    }

    /**
     * 获取指定节点客户端（用于定向请求）
     */
    public Optional<Client> getClient(String peer) {
        return clients.stream()
                .filter(w -> w.getPeer().equals(peer) && w.isHealthy())
                .map(ClientWrapper::getClient)
                .findFirst();
    }

    /**
     * 执行带重试的操作
     */
    public <T> T executeWithRetry(Function<Client, T> operation) {
        int retryTimes = bcosConfig.getConnection().getRetryTimes();
        int retryInterval = bcosConfig.getConnection().getRetryInterval();

        Exception lastException = null;
        for (int i = 0; i <= retryTimes; i++) {
            try {
                Client client = getClient();
                return operation.apply(client);
            } catch (Exception e) {
                lastException = e;
                log.warn("FISCO operation failed (attempt {}/{})", i + 1, retryTimes + 1, e);
                if (i < retryTimes) {
                    try {
                        Thread.sleep(retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new FiscoClientException("Operation interrupted", ie);
                    }
                }
            }
        }
        throw new FiscoClientException("All retry attempts failed", lastException);
    }

    private void startHealthCheck() {
        int interval = bcosConfig.getHealthCheck().getInterval();
        healthChecker.scheduleAtFixedRate(this::checkHealth, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void checkHealth() {
        for (ClientWrapper wrapper : clients) {
            try {
                BigInteger blockNumber = wrapper.getClient()
                        .getBlockNumber()
                        .getBlockNumber();
                wrapper.setHealthy(true);
                wrapper.setLastHealthCheck(System.currentTimeMillis());
                log.debug("Health check passed for {}: block={}", wrapper.getPeer(), blockNumber);
            } catch (Exception e) {
                wrapper.setHealthy(false);
                log.warn("Health check failed for {}: {}", wrapper.getPeer(), e.getMessage());
                tryReconnect(wrapper);
            }
        }
    }

    private void tryReconnect(ClientWrapper wrapper) {
        try {
            Client newClient = createClient(wrapper.getPeer());
            // 替换旧客户端
            clients.stream()
                    .filter(w -> w.getPeer().equals(wrapper.getPeer()))
                    .findFirst()
                    .ifPresent(w -> {
                        w.setClient(newClient);
                        w.setHealthy(true);
                        log.info("Reconnected to peer: {}", wrapper.getPeer());
                    });
        } catch (Exception e) {
            log.error("Reconnect failed for {}", wrapper.getPeer(), e);
        }
    }

    @Override
    public void destroy() {
        healthChecker.shutdown();
        clients.forEach(w -> {
            try {
                w.getClient().stop();
            } catch (Exception e) {
                log.warn("Error stopping client for {}", w.getPeer(), e);
            }
        });
    }
}
```

#### 4.3.2 服务层改造

```java
@DubboService(version = BlockChainService.VERSION_V2)
@Slf4j
public class BlockChainServiceImplV2 implements BlockChainService {

    @Autowired
    private FiscoClientPool clientPool;

    @Override
    public Result<FileDetailVO> getFile(String fileHash, String uploaderParam, String param) {
        return clientPool.executeWithRetry(client -> {
            // 原有逻辑，使用传入的 client
            TransactionResponse response = client.sendCall(
                    contractAddress,
                    "getFile",
                    Arrays.asList(fileHash, uploaderParam, param)
            );
            return parseFileResponse(response);
        });
    }
}
```

### 4.4 健康检查 Actuator 端点

```java
@Component("fisco")
public class FiscoHealthIndicator implements HealthIndicator {

    @Autowired
    private FiscoClientPool clientPool;

    @Override
    public Health health() {
        List<Map<String, Object>> nodeDetails = clientPool.getClients().stream()
                .map(wrapper -> Map.of(
                        "peer", wrapper.getPeer(),
                        "healthy", wrapper.isHealthy(),
                        "lastCheck", wrapper.getLastHealthCheck()
                ))
                .collect(Collectors.toList());

        long healthyCount = clientPool.getClients().stream()
                .filter(ClientWrapper::isHealthy)
                .count();

        if (healthyCount == 0) {
            return Health.down()
                    .withDetail("nodes", nodeDetails)
                    .withDetail("message", "No healthy FISCO nodes")
                    .build();
        } else if (healthyCount < clientPool.getClients().size()) {
            return Health.status("DEGRADED")
                    .withDetail("nodes", nodeDetails)
                    .withDetail("healthyCount", healthyCount)
                    .build();
        }

        return Health.up()
                .withDetail("nodes", nodeDetails)
                .withDetail("healthyCount", healthyCount)
                .build();
    }
}
```

### 4.5 实施步骤

| 步骤 | 任务 | 工时 |
|------|------|------|
| 1 | 更新 BcosConfig 配置类 | 1h |
| 2 | 实现 FiscoClientPool | 3h |
| 3 | 改造 BlockChainServiceImpl 使用客户端池 | 2h |
| 4 | 实现 FiscoHealthIndicator | 1h |
| 5 | 编写单元测试和集成测试 | 2h |
| 6 | 文档和配置示例 | 1h |

---

## 五、P4-3：智能合约优化（预估 12h）

### 5.1 现状分析

**当前架构（已是最佳实践）**：
- `content` 字段存储 **分片索引映射**（`chunkHash → MinIO路径` 的 JSON）
- `param` 字段存储 **加密参数**
- 实际文件内容存储在 MinIO，链上只存元数据

**设计优势**：
1. **不可抵赖**：分片索引上链后无法篡改
2. **避免单点故障**：元数据分布在区块链多节点
3. **数据恢复**：有链上索引就能知道需要恢复哪些分片

**仍需优化的问题**：
1. **事件未 indexed**：无法高效链下查询
2. **分享码生成不安全**：依赖可预测的 `block.difficulty`
3. **Java 层返回值解析使用魔数索引**：合约变更时易出错

**涉及文件**：
```
platform-fisco/src/main/contracts/Storage.sol
platform-fisco/src/main/contracts/Sharing.sol
platform-fisco/src/main/java/cn/flying/fisco_bcos/service/BlockChainServiceImpl.java
```

### 5.2 Storage.sol 事件优化

当前事件定义缺少 `indexed` 关键字，无法高效进行链下查询：

```solidity
// 当前（无索引）
event FileStored(string fileName, string uploader, bytes32 fileHash, uint256 uploadTime);

// 优化后（添加索引）
event FileStored(
    bytes32 indexed fileHash,      // 索引：支持按哈希查询
    string indexed uploaderHash,   // 索引：支持按上传者查询（需哈希化）
    string fileName,
    string uploader,
    uint256 timestamp
);

event FileDeleted(
    bytes32 indexed fileHash,
    string indexed uploaderHash,
    uint256 timestamp
);
```

**注意**：Solidity 中 `string` 类型作为 indexed 参数时会自动哈希，所以需要同时保留原始值和哈希值

### 5.3 Sharing.sol 分享码生成优化

当前分享码生成依赖 `block.difficulty`，在 FISCO BCOS 环境中可预测：

```solidity
// 当前（不安全）
uint256 randomIndex = uint256(keccak256(abi.encodePacked(
    block.timestamp,
    block.difficulty,    // 易预测
    msg.sender,
    nonce,
    i
))) % charsetLength;

// 优化后（多熵源组合）
uint256 randomIndex = uint256(keccak256(abi.encodePacked(
    blockhash(block.number - 1),  // 前一区块哈希
    block.timestamp,
    block.coinbase,               // 出块节点地址
    msg.sender,
    tx.gasprice,                  // 交易 gas 价格
    nonce,
    i,
    gasleft()                     // 剩余 gas（难预测）
))) % charsetLength;
```

### 5.4 Java 返回值解析优化（消除魔数）

当前 `BlockChainServiceImpl` 使用硬编码索引解析返回值：

```java
// 当前（魔数索引）
FileDetailVO fileDetailVO = FileDetailVO.builder()
    .uploader(safeGetString(fileInfo, 0).orElse(""))
    .fileName(safeGetString(fileInfo, 1).orElse(""))
    .param(safeGetString(fileInfo, 2).orElse(""))
    .content(safeGetString(fileInfo, 3).orElse(""))
    // ...
```

**优化方案**：

```java
/**
 * 合约返回值字段索引常量
 */
public final class ContractFieldIndex {

    // Storage.getFile() 返回字段索引
    public static final class FileInfo {
        public static final int UPLOADER = 0;
        public static final int FILE_NAME = 1;
        public static final int PARAM = 2;        // 加密参数
        public static final int CONTENT = 3;      // 分片索引 JSON
        public static final int FILE_HASH = 4;
        public static final int UPLOAD_TIME = 5;
        public static final int FIELD_COUNT = 6;
    }

    // Sharing.getShareInfo() 返回字段索引
    public static final class ShareInfo {
        public static final int FILE_HASH = 0;
        public static final int UPLOADER = 1;
        public static final int MAX_ACCESSES = 2;
        public static final int ACCESS_COUNT = 3;
        public static final int EXPIRATION_TIME = 4;
        public static final int REMAINING_ACCESSES = 5;
        public static final int IS_VALID = 6;
        public static final int FIELD_COUNT = 7;
    }
}

/**
 * 类型安全的合约响应解析器
 */
@Component
public class ContractResponseMapper {

    public Optional<FileDetailVO> mapToFileDetail(List<?> returnList, String fileHash) {
        if (!validateSize(returnList, ContractFieldIndex.FileInfo.FIELD_COUNT)) {
            return Optional.empty();
        }

        try {
            return Optional.of(FileDetailVO.builder()
                .uploader(getString(returnList, ContractFieldIndex.FileInfo.UPLOADER))
                .fileName(getString(returnList, ContractFieldIndex.FileInfo.FILE_NAME))
                .param(getString(returnList, ContractFieldIndex.FileInfo.PARAM))
                .content(getString(returnList, ContractFieldIndex.FileInfo.CONTENT))
                .fileHash(fileHash)
                .uploadTimestamp(getLong(returnList, ContractFieldIndex.FileInfo.UPLOAD_TIME) * 1000)
                .build());
        } catch (Exception e) {
            log.error("Failed to map file detail", e);
            return Optional.empty();
        }
    }

    // 类型安全的取值方法
    private String getString(List<?> list, int index) {
        return Optional.ofNullable(list.get(index)).map(Object::toString).orElse("");
    }

    private Long getLong(List<?> list, int index) {
        Object val = list.get(index);
        if (val instanceof BigInteger bi) return bi.longValue();
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private boolean validateSize(List<?> list, int expectedSize) {
        return list != null && list.size() >= expectedSize;
    }
}
```

### 5.5 实施步骤

| 步骤 | 任务 | 工时 |
|------|------|------|
| 1 | Storage.sol 添加 indexed 事件 | 2h |
| 2 | Sharing.sol 优化随机数生成 | 2h |
| 3 | 合约单元测试 | 2h |
| 4 | 部署更新后的合约 | 1h |
| 5 | 实现 ContractFieldIndex 常量类 | 1h |
| 6 | 实现 ContractResponseMapper | 2h |
| 7 | 改造 BlockChainServiceImpl | 2h |

---

## 六、P4-4：CQRS 与虚拟线程（预估 15h）

### 6.1 CQRS 读写分离架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CQRS 架构演进                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   Command Side                    Query Side                        │
│   ┌─────────────┐                ┌─────────────┐                   │
│   │ FileService │                │ FileQuery   │                   │
│   │ (写入)      │                │ (只读)      │                   │
│   └──────┬──────┘                └──────┬──────┘                   │
│          │                              │                           │
│          ▼                              ▼                           │
│   ┌─────────────┐                ┌─────────────┐                   │
│   │ MySQL       │───────────────▶│ Redis/ES    │                   │
│   │ (主库)      │   Outbox/CDC   │ (读缓存)    │                   │
│   └─────────────┘                └─────────────┘                   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

#### 6.1.1 Query Service 分离

```java
/**
 * 查询服务（只读）
 */
@Service
@Transactional(readOnly = true)
public class FileQueryService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private FileMapper fileMapper;

    private static final String CACHE_KEY_PREFIX = "file:query:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * 查询文件详情（缓存优先）
     */
    public Optional<FileVO> getFile(Long fileId, Long tenantId) {
        String cacheKey = buildCacheKey(tenantId, fileId);

        // 1. 尝试从缓存读取
        FileVO cached = (FileVO) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return Optional.of(cached);
        }

        // 2. 缓存未命中，查询数据库
        FileVO file = fileMapper.selectById(fileId, tenantId);
        if (file != null) {
            redisTemplate.opsForValue().set(cacheKey, file, CACHE_TTL);
        }

        return Optional.ofNullable(file);
    }

    /**
     * 分页查询用户文件列表
     */
    public PageInfo<FileVO> listUserFiles(Long userId, Long tenantId, int page, int size) {
        // 列表查询直接走数据库（或 ES）
        return fileMapper.selectPageByUserId(userId, tenantId, page, size);
    }

    private String buildCacheKey(Long tenantId, Long fileId) {
        return CACHE_KEY_PREFIX + tenantId + ":" + fileId;
    }
}

/**
 * 命令服务（写入）
 */
@Service
public class FileCommandService {

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 创建文件（发布领域事件）
     */
    @Transactional(rollbackFor = Exception.class)
    public FileVO createFile(CreateFileCommand command) {
        // 1. 写入数据库
        File file = command.toEntity();
        fileMapper.insert(file);

        // 2. 发布事件（通过 Outbox 保证一致性）
        FileCreatedEvent event = new FileCreatedEvent(file);
        outboxPublisher.publish("file.created", event);

        // 3. 主动失效缓存
        invalidateCache(file.getTenantId(), file.getId());

        return FileVO.from(file);
    }

    /**
     * 删除文件
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(Long fileId, Long tenantId) {
        fileMapper.softDelete(fileId, tenantId);

        FileDeletedEvent event = new FileDeletedEvent(fileId, tenantId);
        outboxPublisher.publish("file.deleted", event);

        invalidateCache(tenantId, fileId);
    }

    private void invalidateCache(Long tenantId, Long fileId) {
        String cacheKey = "file:query:" + tenantId + ":" + fileId;
        redisTemplate.delete(cacheKey);
    }
}
```

#### 6.1.2 事件消费者更新物化视图

```java
@Component
@Slf4j
public class FileEventConsumer {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ElasticsearchClient esClient;  // 可选：全文搜索

    @RabbitListener(queues = "file.events")
    public void handleFileEvent(FileEvent event) {
        switch (event.getType()) {
            case "file.created" -> handleFileCreated((FileCreatedEvent) event);
            case "file.updated" -> handleFileUpdated((FileUpdatedEvent) event);
            case "file.deleted" -> handleFileDeleted((FileDeletedEvent) event);
        }
    }

    private void handleFileCreated(FileCreatedEvent event) {
        // 更新用户文件计数
        String countKey = "user:file:count:" + event.getTenantId() + ":" + event.getUserId();
        redisTemplate.opsForValue().increment(countKey);

        // 可选：索引到 ES
        if (esClient != null) {
            indexToElasticsearch(event.getFile());
        }

        log.info("Processed file.created event: fileId={}", event.getFileId());
    }

    private void handleFileDeleted(FileDeletedEvent event) {
        String countKey = "user:file:count:" + event.getTenantId() + ":" + event.getUserId();
        redisTemplate.opsForValue().decrement(countKey);

        if (esClient != null) {
            removeFromElasticsearch(event.getFileId());
        }

        log.info("Processed file.deleted event: fileId={}", event.getFileId());
    }
}
```

### 6.2 Java 21 虚拟线程集成

#### 6.2.1 虚拟线程执行器配置

```java
@Configuration
@EnableAsync
public class VirtualThreadConfig {

    /**
     * 虚拟线程执行器（用于 @Async 任务）
     */
    @Bean("virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 带 MDC 传播的虚拟线程执行器
     */
    @Bean("mdcVirtualThreadExecutor")
    public Executor mdcVirtualThreadExecutor() {
        return new MdcPropagatingExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * MDC 传播包装器
     */
    public static class MdcPropagatingExecutor implements Executor {
        private final Executor delegate;

        public MdcPropagatingExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            Long tenantId = TenantContext.getTenantId();

            delegate.execute(() -> {
                try {
                    if (contextMap != null) MDC.setContextMap(contextMap);
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    command.run();
                } finally {
                    MDC.clear();
                    TenantContext.clear();
                }
            });
        }
    }
}
```

#### 6.2.2 Tomcat 虚拟线程配置

```yaml
# application.yml
server:
  tomcat:
    threads:
      virtual: true  # Spring Boot 3.2+ 支持
```

或通过配置类：

```java
@Configuration
public class TomcatVirtualThreadConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadCustomizer() {
        return protocolHandler -> {
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        };
    }
}
```

#### 6.2.3 并行流优化示例

```java
@Service
public class FileProcessingService {

    /**
     * 使用虚拟线程并行处理文件
     */
    public List<FileProcessResult> processFilesInParallel(List<Long> fileIds) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileProcessResult>> futures = fileIds.stream()
                    .map(id -> executor.submit(() -> processFile(id)))
                    .toList();

            return futures.stream()
                    .map(this::getFutureResult)
                    .toList();
        }
    }

    /**
     * 使用 StructuredTaskScope（Java 21 预览特性）
     */
    public FileValidationResult validateFilesStructured(List<Long> fileIds) throws Exception {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            List<StructuredTaskScope.Subtask<Boolean>> subtasks = fileIds.stream()
                    .map(id -> scope.fork(() -> validateFile(id)))
                    .toList();

            scope.join();
            scope.throwIfFailed();

            boolean allValid = subtasks.stream()
                    .allMatch(t -> t.get());

            return new FileValidationResult(allValid, fileIds.size());
        }
    }
}
```

### 6.3 实施步骤

| 步骤 | 任务 | 工时 |
|------|------|------|
| 1 | 拆分 FileQueryService / FileCommandService | 3h |
| 2 | 实现缓存更新事件消费者 | 2h |
| 3 | 配置虚拟线程执行器 | 1h |
| 4 | Tomcat 虚拟线程配置 | 1h |
| 5 | MDC 传播适配 | 2h |
| 6 | 性能测试和调优 | 3h |
| 7 | 文档更新 | 3h |

---

## 七、技术债务清单（剩余项）

| 债务项 | 严重度 | 位置 | 建议 |
|--------|--------|------|------|
| 测试覆盖率 <10% | 🔴 高 | 全部模块 | 补充核心路径单测 |
| FileDetailVO 缺 fileHash | 🟡 中 | api/response | P4-1 DTO 补全时修复 |
| 事件未 indexed | 🟡 中 | Storage.sol/Sharing.sol | P4-3 实施 |
| 分享码生成依赖可预测熵源 | 🟡 中 | Sharing.sol | P4-3 实施 |
| 返回值魔数索引 | 🟡 中 | BlockChainServiceImpl | P4-3 实施 |
| 日志语言混用 | 🟢 低 | 全部 | 统一中文 |
| ResultEnum 编码混乱 | 🟢 低 | api/constant | 重新规划分段 |
| Caffeine 无预热 | 🟢 低 | backend-web | 启动时预热 |
| OkHttpClient 未关闭 | 🟢 低 | MinioMonitor | PreDestroy 清理 |

---

## 八、行动项汇总

| 优先级 | 任务 | 预估工时 | 依赖 |
|--------|------|----------|------|
| **P4-1** | API 版本化框架 | 10h | 无 |
| **P4-2** | 区块链高可用 | 10h | 无 |
| **P4-3** | 智能合约优化 | 12h | 无 |
| **P4-4** | CQRS/虚拟线程 | 15h | 无 |

**建议实施顺序**：P4-1 → P4-2 → P4-3 → P4-4

- P4-1/P4-2/P4-3/P4-4 均可并行推进（无强依赖）
- P4-2 和 P4-3 可优先，因为涉及区块链基础设施稳定性

---

## 九、关键文件索引

| 功能 | 文件路径 |
|------|---------|
| **API 接口定义** | `platform-api/src/main/java/cn/flying/platformapi/external/BlockChainService.java` |
| **REST Controller** | `platform-backend/backend-web/src/main/java/cn/flying/controller/FileController.java` |
| **FISCO 配置** | `platform-fisco/src/main/resources/application.yml` |
| **SDK 配置** | `platform-fisco/src/main/java/cn/flying/fisco_bcos/config/SdkBeanConfig.java` |
| **Storage 合约** | `platform-fisco/src/main/contracts/Storage.sol` |
| **Sharing 合约** | `platform-fisco/src/main/contracts/Sharing.sol` |
| **返回值解析** | `platform-fisco/src/main/java/cn/flying/fisco_bcos/service/BlockChainServiceImpl.java` |

---

## 十、总结

P4 架构升级聚焦四个核心方向：

1. **API 版本化**：实现多版本并行、平滑升级、契约测试
2. **区块链高可用**：多节点配置、故障转移、健康检查
3. **智能合约优化**：事件索引、分享码安全、返回值解析（当前"索引上链、内容链下"架构已是最佳实践）
4. **CQRS/虚拟线程**：读写分离、Java 21 特性利用

总预估工时：**47h**

项目整体架构设计合理，Saga + Outbox 模式是业界最佳实践。P0-P3 阶段已全部完成，系统已具备生产环境部署条件。P4 阶段聚焦长期架构演进，可根据业务优先级灵活调整实施顺序。
