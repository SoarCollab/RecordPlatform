# 测试策略

## 测试分层

| 命名约定 | 类型 | 运行方式 | 说明 |
|----------|------|----------|------|
| `*Test.java` | 单元测试 | Maven Surefire | 无外部依赖（DB/Redis/MQ/Nacos） |
| `*IT.java` | 集成测试 | Maven Failsafe（`-Pit`） | Testcontainers 自动启动 MySQL + RabbitMQ，需 Docker |

## 运行测试

### 后端

```bash
# 首次或依赖变更时安装共享接口
mvn -f platform-api/pom.xml clean install -DskipTests

# 单元测试（无需 Docker）
mvn -f platform-backend/pom.xml test -pl backend-common,backend-service,backend-web -am

# 集成测试（需 Docker）
mvn -f platform-backend/pom.xml verify -pl backend-service,backend-web -am -Pit

# 单个测试类
mvn -f platform-backend/pom.xml -pl backend-service test -Dtest=FileUploadServiceTest

# FISCO 服务测试
mvn -f platform-fisco/pom.xml test

# 存储服务测试
mvn -f platform-storage/pom.xml test
```

### 前端

```bash
cd platform-frontend
pnpm test                                           # 全部测试
pnpm test:coverage                                  # 带覆盖率报告
pnpm test -- src/lib/stores/auth.svelte.test.ts     # 单个文件
```

## 测试工具类

测试构造器位于 `backend-service/src/test/.../builders/`：

| 构造器 | 使用方式 |
|--------|----------|
| `FileTestBuilder` | `FileTestBuilder.aFile()` |
| `AccountTestBuilder` | `AccountTestBuilder.anAccount(a -> a.setUsername("test"))` |
| `FileUploadStateTestBuilder` | `FileUploadStateTestBuilder.aState()` |
| `FriendRequestTestBuilder` | `FriendRequestTestBuilder.aRequest()` |
| `FileShareTestBuilder` | `FileShareTestBuilder.aShare()` |
| `TicketTestBuilder` | `TicketTestBuilder.aTicket()` |

::: warning 重要
在测试类上必须添加 `@ExtendWith(BuilderResetExtension.class)` 以隔离测试间的 ID 计数器。
:::

## Controller 集成测试

基类 `BaseControllerIntegrationTest`（`backend-web/src/test/.../support/`）：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(BuilderResetExtension.class)
class FileControllerIT extends BaseControllerIntegrationTest {

    @Test
    void getFiles_returnsLatestVersionsOnly() throws Exception {
        setTestUser(1L, 1L);  // userId, tenantId
        performGet("/api/v1/files")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }
}
```

**基类提供的工具方法：**
- `performGet/Post/Put/Delete(url)` — 自动注入 JWT 和 tenant header
- `expectOk(actions)` — 断言 200 + `code=200`
- `extractData(result, Class)` — 从响应中提取 data 字段
- `setTestUser(userId, tenantId)` / `setTestAdmin(userId, tenantId)` — 设置请求身份

## JDK 21 Mockito 配置

JDK 21+ 需要 Byte Buddy agent 用于 Mockito inline mocking。已在 `maven-surefire-plugin` argLine 中配置，CI 环境自动生效。

IDE（IntelliJ）中运行测试时需添加 JVM 参数：

```
-javaagent:<path>/byte-buddy-agent-1.14.19.jar -Djdk.attach.allowAttachSelf=true
```
