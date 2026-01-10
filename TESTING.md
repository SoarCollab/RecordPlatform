# 测试框架说明（最少代码 + 关键覆盖）

本项目采用"单元测试优先 + 少量高价值集成测试"的策略，目标是在 CI 中尽早发现回归，同时保持本地开发的执行成本足够低。

## 当前测试覆盖（193 unit tests + 集成测试）

### 后端单元测试（backend-service）

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| FileUploadServiceTest | 20 | 分块上传、暂停/恢复、状态管理、所有权验证 |
| FileServiceTest | 21 | 分享生成（公开/私密）、取消/更新分享、访问计数 |
| FileQueryServiceTest | 19 | 文件访问控制、好友分享权限、管理员权限、JSON 验证 |
| FriendServiceTest | 21 | 好友请求生命周期、接受/拒绝/取消、解除好友 |
| TicketServiceTest | 21 | 工单创建、回复、状态转换、分配 |

### 后端单元测试（backend-web）

| 测试类 | 测试数 | 覆盖范围 |
|--------|--------|----------|
| JwtAuthenticationFilterTest | 多个 | JWT 验证、安全上下文 |
| TenantFilterTest | 多个 | 租户解析、ThreadLocal 清理 |

### 后端其他测试

| 测试类 | 类型 | 覆盖范围 |
|--------|------|----------|
| ChunkEncryptionStrategyTest | 单元 | AES-GCM/ChaCha20 加密（参数化） |
| SseEmitterManagerTest | 单元 | SSE 连接管理、并发 |
| FileSagaOrchestratorTest | 单元 | Saga 状态机、补偿逻辑 |
| DatabaseIT | 集成 | Flyway 迁移、MyBatis-Plus、SecureId AOP |

### 前端测试（platform-frontend）

| 测试类 | 覆盖范围 |
|--------|----------|
| client.test.ts | Token 管理、ApiError |
| crypto.test.ts | 文件解密、加密头解析 |
| validation.test.ts | 输入验证规则 |

### 测试工具类

| 工具类 | 用途 |
|--------|------|
| FileTestBuilder | File 实体测试数据构建器 |
| FileUploadStateTestBuilder | 上传状态测试数据构建器 |
| AccountTestBuilder | Account 实体测试数据构建器 |
| FriendRequestTestBuilder | 好友请求测试数据构建器 |

## 1. 测试分层约定

- **单元测试（Unit）**：命名 `*Test.java`，由 Maven Surefire 在 `test` 阶段执行  
  - 特点：不依赖外部系统（DB/Redis/MQ/Nacos/Dubbo），执行快、可并行、定位问题清晰
  - 建议：单测里尽量避免打印异常堆栈（用 mock + 断言覆盖失败分支即可）
- **集成测试（Integration）**：命名 `*IT.java`，由 Maven Failsafe 在 `verify` 阶段执行（需要启用 `it` Profile）  
  - 特点：使用 Testcontainers 启动依赖（如 MySQL/Redis/RabbitMQ），覆盖 Flyway、MyBatis、AOP、关键基础设施联动

## 2. 后端（platform-backend）运行方式

> 说明：`backend-service` 依赖 `platform-api`，本地跑后端测试前需要先安装 `platform-api` 到本地仓库。

### 2.1 安装 platform-api（一次性 / 依赖更新时）

```bash
mvn -f platform-api/pom.xml clean install -DskipTests
```

### 2.2 仅跑单元测试（不需要 Docker）

```bash
mvn -f platform-backend/pom.xml test -pl backend-service,backend-web -am
```

### 2.3 跑集成测试（需要 Docker，使用 Testcontainers）

```bash
mvn -f platform-backend/pom.xml verify -pl backend-service,backend-web -am -Pit
```

> 说明：本地没有 Docker 时，集成测试会自动跳过（不会导致构建失败）。

## 3. 前端（platform-frontend）运行方式

```bash
cd platform-frontend
pnpm test:coverage
```

## 4. 其他服务测试（可选）

### 4.1 区块链服务（platform-fisco）

```bash
mvn -f platform-fisco/pom.xml test
```

### 4.2 存储服务（platform-storage）

```bash
mvn -f platform-storage/pom.xml test
```

#### 存储服务测试覆盖

| 测试类 | 类型 | 覆盖范围 |
|--------|------|----------|
| ConsistentHashRingTest | 单元 | 一致性哈希算法、虚拟节点分布 |
| FaultDomainManagerTest | 单元 | 故障域管理、节点状态转换 |
| StandbyPoolManagerTest | 单元 | 备用节点池、自动提升逻辑 |
| RebalanceServiceTest | 单元 | 数据再平衡、限流控制 |
| ConsistencyRepairServiceTest | 单元 | 一致性修复、统计与调度条件 |
| DistributedStorageServiceImplTest | 单元 | 存储/查询/删除/健康检查、再平衡触发 |

#### Mock 策略

存储服务测试使用以下 Mock 策略：
- **S3 客户端**: 使用 Mockito Mock `S3Client`
- **Redis**: 使用内嵌 Redis 或 Mock `RedisTemplate`
- **事件发布**: Mock `ApplicationEventPublisher`

## 5. CI 执行策略

- GitHub Actions 会执行：
  - 后端：`mvn -f platform-backend/pom.xml clean verify -pl backend-service,backend-web -am -Pit`
  - FISCO：`mvn -f platform-fisco/pom.xml test`
  - Storage：`mvn -f platform-storage/pom.xml test`
  - 前端：`pnpm test:coverage`
- 后端覆盖率报告由 JaCoCo 生成，CI 中会上传 `jacoco.xml`（见 `.github/workflows/test.yml`）。

## 6. 新增测试的建议（保持“最少代码”）

- 优先给 **纯业务逻辑** 写单元测试：无 Spring 上下文、无外部依赖、直接 new / Mockito 即可
- 只为最关键链路写少量集成测试：数据库迁移 + ORM 映射 + 关键 AOP/拦截器
- 单测不要依赖执行顺序；集成测试也尽量使用随机/唯一数据，避免与其他用例耦合
