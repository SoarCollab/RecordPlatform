# Testing Strategy

## Test Layers

| Naming Convention | Type | Runner | Description |
|-------------------|------|--------|-------------|
| `*Test.java` | Unit tests | Maven Surefire | No external dependencies (DB/Redis/MQ/Nacos) |
| `*IT.java` | Integration tests | Maven Failsafe (`-Pit`) | Testcontainers auto-starts MySQL + RabbitMQ; requires Docker |

## Running Tests

### Backend

```bash
# Install shared interfaces first (one-time / when dependencies change)
mvn -f platform-api/pom.xml clean install -DskipTests

# Unit tests only (no Docker needed)
mvn -f platform-backend/pom.xml test -pl backend-common,backend-service,backend-web -am

# Integration tests (requires Docker)
mvn -f platform-backend/pom.xml verify -pl backend-service,backend-web -am -Pit

# Single test class
mvn -f platform-backend/pom.xml -pl backend-service test -Dtest=FileUploadServiceTest

# FISCO service tests
mvn -f platform-fisco/pom.xml test

# Storage service tests
mvn -f platform-storage/pom.xml test
```

### Frontend

```bash
cd platform-frontend
pnpm test                                           # all tests
pnpm test:coverage                                  # with coverage report
pnpm test -- src/lib/stores/auth.svelte.test.ts     # single file
```

## Test Builders

Test builders in `backend-service/src/test/.../builders/`:

| Builder | Usage |
|---------|-------|
| `FileTestBuilder` | `FileTestBuilder.aFile()` |
| `AccountTestBuilder` | `AccountTestBuilder.anAccount(a -> a.setUsername("test"))` |
| `FileUploadStateTestBuilder` | `FileUploadStateTestBuilder.aState()` |
| `FriendRequestTestBuilder` | `FriendRequestTestBuilder.aRequest()` |
| `FileShareTestBuilder` | `FileShareTestBuilder.aShare()` |
| `TicketTestBuilder` | `TicketTestBuilder.aTicket()` |

::: warning Important
Add `@ExtendWith(BuilderResetExtension.class)` to test classes to isolate ID counters between tests.
:::

## Controller Integration Tests

Base class `BaseControllerIntegrationTest` (`backend-web/src/test/.../support/`):

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

**Provided utilities:**
- `performGet/Post/Put/Delete(url)` — auto-injects JWT and tenant header
- `expectOk(actions)` — asserts HTTP 200 + `code=200`
- `extractData(result, Class)` — extracts `data` field from response
- `setTestUser(userId, tenantId)` / `setTestAdmin(userId, tenantId)` — sets request identity

## JDK 21 Mockito Setup

JDK 21+ requires the Byte Buddy agent for Mockito inline mocking. Already configured in `maven-surefire-plugin` argLine for CI.

For IDE runs, add VM options:

```
-javaagent:<path>/byte-buddy-agent-1.14.19.jar -Djdk.attach.allowAttachSelf=true
```
