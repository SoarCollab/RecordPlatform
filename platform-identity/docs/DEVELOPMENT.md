# API Gateway 开发指南

## 目录

- [开发环境搭建](#开发环境搭建)
- [项目结构说明](#项目结构说明)
- [编码规范](#编码规范)
- [核心组件开发](#核心组件开发)
- [扩展开发](#扩展开发)
- [测试指南](#测试指南)
- [调试技巧](#调试技巧)
- [常见问题](#常见问题)

## 开发环境搭建

### 1. 环境要求

- **JDK**: 21 或更高版本
- **Maven**: 3.6 或更高版本
- **IDE**: IntelliJ IDEA 2023.2+ (推荐) 或 Eclipse
- **MySQL**: 8.0+
- **Redis**: 7.0+
- **Git**: 2.30+

### 2. 项目导入

```bash
# 克隆项目
git clone https://github.com/your-org/RecordPlatform.git
cd RecordPlatform/platform-identity

# 安装依赖
mvn clean install -DskipTests

# 导入IDE
# IntelliJ IDEA: File -> Open -> 选择pom.xml
# Eclipse: Import -> Existing Maven Projects
```

### 3. 数据库初始化

```sql
-- 创建数据库
CREATE DATABASE platform_identity CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 执行初始化脚本
USE platform_identity;

-- 创建API网关相关表
CREATE TABLE api_application (
    id BIGINT PRIMARY KEY,
    app_name VARCHAR(100) NOT NULL,
    app_code VARCHAR(50) UNIQUE NOT NULL,
    app_description TEXT,
    owner_id BIGINT NOT NULL,
    app_type INT NOT NULL,
    app_status INT DEFAULT 0,
    app_icon VARCHAR(500),
    app_website VARCHAR(500),
    callback_url TEXT,
    ip_whitelist JSON,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    approve_time DATETIME,
    approve_by BIGINT,
    deleted INT DEFAULT 0,
    INDEX idx_owner(owner_id),
    INDEX idx_status(app_status),
    INDEX idx_code(app_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE api_key (
    id BIGINT PRIMARY KEY,
    app_id BIGINT NOT NULL,
    api_key VARCHAR(100) UNIQUE NOT NULL,
    api_secret VARCHAR(500) NOT NULL,
    key_name VARCHAR(100),
    key_status INT DEFAULT 1,
    key_type INT NOT NULL,
    expire_time DATETIME,
    last_used_time DATETIME,
    used_count BIGINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted INT DEFAULT 0,
    INDEX idx_app(app_id),
    INDEX idx_key(api_key),
    INDEX idx_status(key_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 其他表结构...
```

### 4. 启动应用

```bash
# 使用Maven启动
mvn spring-boot:run -Dspring.profiles.active=local

# 或使用IDE启动
# 主类: cn.flying.identity.IdentityApplication
# VM参数: -Dspring.profiles.active=local
```

## 项目结构说明

```
platform-identity/
├── src/main/java/cn/flying/identity/
│   ├── annotation/              # 自定义注解
│   ├── aspect/                  # 切面类
│   │   ├── ApiPerformanceAspect.java   # 性能监控切面
│   │   └── OperationLogAspect.java     # 操作日志切面
│   ├── config/                  # 配置类
│   │   └── ApiGatewayConfig.java       # API网关配置
│   ├── constant/                # 常量定义
│   │   └── ApiGatewayConstants.java    # API网关常量
│   ├── controller/              # 控制器层
│   │   └── apigateway/          # API网关控制器
│   │       ├── BaseApiGatewayController.java
│   │       ├── ApiApplicationController.java
│   │       ├── ApiKeyController.java
│   │       └── ApiPermissionController.java
│   ├── dto/                     # 数据传输对象
│   │   └── apigateway/          # API网关实体
│   │       ├── ApiApplication.java
│   │       ├── ApiKey.java
│   │       └── ApiPermission.java
│   ├── exception/               # 异常处理
│   │   ├── BusinessException.java
│   │   └── GlobalExceptionHandler.java
│   ├── filter/                  # 过滤器
│   ├── mapper/                  # MyBatis映射
│   │   └── apigateway/          # API网关Mapper
│   ├── service/                 # 服务接口
│   │   ├── BaseService.java
│   │   └── apigateway/          # API网关服务
│   ├── service/impl/            # 服务实现
│   │   └── apigateway/          # API网关服务实现
│   ├── util/                    # 工具类
│   └── vo/                      # 视图对象
└── src/main/resources/
    ├── mapper/                  # MyBatis XML映射
    └── application*.yml         # 配置文件
```

## 编码规范

### 1. 命名规范

```java
// 类名：大驼峰命名
public class ApiApplicationService { }

// 方法名：小驼峰命名，动词开头
public Result<ApiApplication> registerApplication() { }

// 常量：全大写，下划线分隔
public static final int MAX_RETRY_TIMES = 3;

// 变量：小驼峰命名
private String apiKey;

// 包名：全小写
package cn.flying.identity.service.apigateway;
```

### 2. 注释规范

```java
/**
 * API应用管理服务
 * 提供应用注册、审核、管理等功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public class ApiApplicationService {

    /**
     * 注册新应用
     *
     * @param appName 应用名称
     * @param ownerId 所有者ID
     * @return 注册结果，包含应用信息
     * @throws BusinessException 业务异常
     */
    public Result<Map<String, Object>> registerApplication(String appName, Long ownerId) {
        // 参数验证
        requireNonBlank(appName, "应用名称不能为空");

        // 生成应用标识码
        String appCode = generateAppCode();

        // TODO: 后续需要添加邮件通知功能
    }
}
```

### 3. 异常处理

```java
// 使用自定义业务异常
throw new BusinessException(ApiGatewayConstants.ErrorCode.APP_NOT_FOUND,
                          ApiGatewayConstants.ErrorMessage.APP_NOT_FOUND);

// 使用Result包装返回值
return Result.error(ResultEnum.PARAM_IS_INVALID, null);

// 使用safeExecute处理异常
return safeExecuteData(() -> {
    // 业务逻辑
    return data;
}, "操作失败");
```

## 核心组件开发

### 1. 控制器开发

```java
@RestController
@RequestMapping("/api/gateway/demo")
@Tag(name = "示例接口", description = "API网关示例接口")
@SaCheckLogin  // 需要登录
public class DemoController extends BaseApiGatewayController {

    @Resource
    private DemoService demoService;

    @PostMapping("/create")
    @Operation(summary = "创建示例")
    @SaCheckPermission("demo:create")  // 需要权限
    public Result<DemoEntity> create(@RequestBody @Validated DemoVO vo) {
        // 获取当前用户ID
        Long userId = requireCurrentUserId();

        // 调用服务
        return demoService.create(vo, userId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取详情")
    public Result<DemoEntity> getById(@PathVariable Long id) {
        return demoService.getById(id);
    }
}
```

### 2. 服务层开发

```java
@Service
@Slf4j
public class DemoServiceImpl extends BaseService implements DemoService {

    @Resource
    private DemoMapper demoMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<DemoEntity> create(DemoVO vo, Long userId) {
        return safeExecuteData(() -> {
            // 参数验证
            requireNonBlank(vo.getName(), "名称不能为空");
            requireCondition(vo.getType(),
                           ApiGatewayConstants.DemoType::isValid,
                           "类型无效");

            // 构建实体
            DemoEntity entity = new DemoEntity();
            entity.setId(IdUtils.nextEntityId());
            entity.setName(vo.getName());
            entity.setType(vo.getType());
            entity.setCreatedBy(userId);

            // 保存到数据库
            int rows = demoMapper.insert(entity);
            requireCondition(rows, r -> r > 0, "保存失败");

            // 缓存到Redis
            cacheEntity(entity);

            // 记录日志
            logInfo("创建示例成功: id={}, name={}", entity.getId(), entity.getName());

            return entity;
        }, "创建示例失败");
    }

    /**
     * 缓存实体
     */
    private void cacheEntity(DemoEntity entity) {
        try {
            String key = ApiGatewayConstants.RedisKey.DEMO_PREFIX + entity.getId();
            String value = JSONUtil.toJsonStr(entity);
            redisTemplate.opsForValue().set(key, value,
                                           ApiGatewayConstants.TimeConstants.ONE_HOUR,
                                           TimeUnit.SECONDS);
        } catch (Exception e) {
            logError("缓存实体失败", e);
            // 缓存失败不影响主流程
        }
    }
}
```

### 3. 数据访问层开发

```java
@Mapper
public interface DemoMapper extends BaseMapper<DemoEntity> {

    /**
     * 自定义查询方法
     */
    @Select("SELECT * FROM demo WHERE type = #{type} AND deleted = 0")
    List<DemoEntity> selectByType(@Param("type") Integer type);

    /**
     * 使用XML映射的复杂查询
     */
    Page<DemoEntity> selectPageWithCondition(@Param("page") Page<DemoEntity> page,
                                            @Param("condition") QueryCondition condition);
}
```

### 4. 常量定义

```java
public class ApiGatewayConstants {

    /**
     * 示例类型
     */
    public static final class DemoType {
        public static final int TYPE_A = 1;
        public static final int TYPE_B = 2;

        public static boolean isValid(Integer type) {
            return type != null && (type == TYPE_A || type == TYPE_B);
        }
    }

    /**
     * Redis键前缀
     */
    public static final class RedisKey {
        public static final String DEMO_PREFIX = "api:demo:";
    }

    /**
     * 错误码
     */
    public static final class ErrorCode {
        public static final int DEMO_NOT_FOUND = 4001;
    }

    /**
     * 错误消息
     */
    public static final class ErrorMessage {
        public static final String DEMO_NOT_FOUND = "示例不存在";
    }
}
```

## 扩展开发

### 1. 添加新的验证器

```java
@Component
public class ApiKeyValidator {

    /**
     * 验证API密钥格式
     */
    public boolean validateApiKey(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return false;
        }

        // API Key格式: ak_32位字符
        return apiKey.matches("^ak_[a-zA-Z0-9]{32}$");
    }

    /**
     * 验证签名
     */
    public boolean validateSignature(String apiKey, String apiSecret,
                                    Long timestamp, String nonce,
                                    String signature, String data) {
        // 构建签名字符串
        String signString = apiKey + timestamp + nonce + (data != null ? data : "");

        // 计算签名
        String calculated = SecureUtil.hmacSha256(apiSecret).digestHex(signString);

        // 比较签名
        return calculated.equalsIgnoreCase(signature);
    }
}
```

### 2. 添加新的切面

```java
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate redisTemplate;

    @Around("@annotation(rateLimit)")
    public Object rateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 获取限流key
        String key = getRateLimitKey(joinPoint, rateLimit);

        // 检查限流
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == 1) {
            redisTemplate.expire(key, rateLimit.period(), TimeUnit.SECONDS);
        }

        if (count > rateLimit.limit()) {
            throw new BusinessException(429, "请求频率超限");
        }

        // 执行方法
        return joinPoint.proceed();
    }
}
```

### 3. 添加新的过滤器

```java
@Component
public class ApiSignatureFilter extends OncePerRequestFilter {

    @Resource
    private ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

        // 获取请求头
        String apiKey = request.getHeader("X-API-Key");
        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        String signature = request.getHeader("X-Signature");

        // 跳过不需要签名的接口
        if (shouldSkip(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 验证签名
        if (!validateSignature(apiKey, timestamp, nonce, signature, request)) {
            writeErrorResponse(response, 403, "签名验证失败");
            return;
        }

        // 继续处理
        filterChain.doFilter(request, response);
    }
}
```

## 测试指南

### 1. 单元测试

```java
@SpringBootTest
@AutoConfigureMockMvc
class ApiApplicationServiceTest {

    @Autowired
    private ApiApplicationService applicationService;

    @MockBean
    private ApiApplicationMapper applicationMapper;

    @Test
    void testRegisterApplication_Success() {
        // Given
        String appName = "测试应用";
        Long ownerId = 123456L;
        Integer appType = 1;

        // Mock
        when(applicationMapper.insert(any())).thenReturn(1);

        // When
        Result<Map<String, Object>> result = applicationService.registerApplication(
            appName, null, ownerId, appType, null, null);

        // Then
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(appName, result.getData().get("app_name"));
    }

    @Test
    void testRegisterApplication_InvalidParams() {
        // Given
        String appName = "";  // 空名称
        Long ownerId = 123456L;

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            applicationService.registerApplication(appName, null, ownerId, 1, null, null);
        });
    }
}
```

### 2. 集成测试

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ApiApplicationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "test@example.com")
    void testRegisterApplication() throws Exception {
        // 准备请求数据
        Map<String, Object> request = new HashMap<>();
        request.put("appName", "测试应用");
        request.put("appType", 1);

        // 执行请求
        mockMvc.perform(post("/api/gateway/application/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSONUtil.toJsonStr(request))
                .header("Authorization", "Bearer test_token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.app_name").value("测试应用"));
    }
}
```

### 3. 性能测试

```java
@Test
void performanceTest() {
    int threadCount = 100;
    int requestPerThread = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);

    long startTime = System.currentTimeMillis();

    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            for (int j = 0; j < requestPerThread; j++) {
                // 调用接口
                apiService.someMethod();
            }
            latch.countDown();
        }).start();
    }

    latch.await();
    long endTime = System.currentTimeMillis();

    System.out.println("总耗时: " + (endTime - startTime) + "ms");
    System.out.println("QPS: " + (threadCount * requestPerThread * 1000 / (endTime - startTime)));
}
```

## 调试技巧

### 1. 日志调试

```java
// 使用不同级别的日志
log.debug("调试信息: {}", data);
log.info("普通信息: {}", data);
log.warn("警告信息: {}", data);
log.error("错误信息", exception);

// 条件日志
if (log.isDebugEnabled()) {
    log.debug("复杂对象: {}", JSONUtil.toJsonStr(complexObject));
}
```

### 2. 远程调试

```bash
# 启动应用时添加调试参数
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -jar app.jar

# IDE配置Remote Debug
# Host: localhost
# Port: 5005
```

### 3. 性能分析

```java
// 使用StopWatch统计耗时
StopWatch stopWatch = new StopWatch("任务名称");

stopWatch.start("子任务1");
// 执行任务1
stopWatch.stop();

stopWatch.start("子任务2");
// 执行任务2
stopWatch.stop();

log.info("执行统计: {}", stopWatch.prettyPrint());
```

## 常见问题

### 1. 如何处理跨域问题？

```java
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
```

### 2. 如何实现接口幂等性？

```java
@Component
public class IdempotentInterceptor {

    public boolean checkIdempotent(String key, long expireSeconds) {
        String redisKey = "idempotent:" + key;

        // 使用Redis的setNx实现
        Boolean success = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, "1", expireSeconds, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }
}
```

### 3. 如何优化数据库查询？

```java
// 1. 使用索引
@TableField(index = true)
private String apiKey;

// 2. 避免N+1查询
List<ApiApplication> apps = applicationMapper.selectListWithKeys();

// 3. 使用分页
Page<ApiApplication> page = new Page<>(1, 10);
IPage<ApiApplication> result = applicationMapper.selectPage(page, wrapper);

// 4. 只查询需要的字段
wrapper.select("id", "app_name", "app_status");
```

### 4. 如何处理大文件上传？

```java
@PostMapping("/upload")
public Result<String> upload(@RequestParam("file") MultipartFile file) {
    // 检查文件大小
    if (file.getSize() > 10 * 1024 * 1024) {  // 10MB
        return Result.error("文件太大");
    }

    // 分块上传
    try (InputStream inputStream = file.getInputStream()) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            // 处理数据块
            processChunk(buffer, bytesRead);
        }
    }

    return Result.success("上传成功");
}
```

## 最佳实践

### 1. 使用常量而非魔法值

```java
// 不好的写法
if (status == 1) { }

// 好的写法
if (status == ApiGatewayConstants.AppStatus.ENABLED) { }
```

### 2. 合理使用事务

```java
// 只在需要的地方使用事务
@Transactional(rollbackFor = Exception.class)
public void updateWithTransaction() {
    // 多个数据库操作
}

// 只读操作使用只读事务
@Transactional(readOnly = true)
public List<Entity> queryList() {
    return mapper.selectList();
}
```

### 3. 优雅处理异常

```java
// 使用try-with-resources
try (InputStream is = new FileInputStream(file)) {
    // 处理文件
}

// 使用Optional避免空指针
Optional.ofNullable(entity)
    .map(Entity::getName)
    .orElse("默认名称");
```

### 4. 遵循RESTful规范

```java
GET    /api/apps          // 获取列表
GET    /api/apps/{id}     // 获取详情
POST   /api/apps          // 创建
PUT    /api/apps/{id}     // 更新
DELETE /api/apps/{id}     // 删除
```

---

*文档版本: v2.0.0*  
*最后更新: 2025-10-15*  
*Java 版本: 21*  
*Spring Boot 版本: 3.2.11*  
*Sa-Token 版本: 1.44.0*  
*MyBatis Plus 版本: 3.5.9*  
*服务端口: 8888*