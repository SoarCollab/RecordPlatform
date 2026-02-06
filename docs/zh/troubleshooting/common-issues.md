# 常见问题

常见问题的解决方案。

## 启动问题

### 服务无法启动

**症状**：应用启动后立即退出。

**可能原因**：

1. **Nacos 不可达**
   ```
   Error: Failed to connect to Nacos server
   ```
   **解决方案**：确保 Nacos 在 8848 端口运行。
   ```bash
   curl http://localhost:8848/nacos/v1/console/health/liveness
   ```

2. **数据库连接失败**
   ```
   Error: Communications link failure
   ```
   **解决方案**：验证 MySQL 正在运行且凭据正确。
   ```bash
   mysql -h <mysql-host> -u <mysql-user> -p RecordPlatform
   ```

3. **端口被占用**
   ```
   Error: Address already in use: 8000
   ```
   **解决方案**：终止占用进程或使用其他端口。
   ```bash
   lsof -i :8000
   kill -9 <PID>
   ```

### Dubbo 服务注册失败

**症状**：Provider 服务在 Nacos 控制台不可见。

**解决方案**：
1. 检查配置中的 Nacos 地址
2. 验证到 Nacos 的网络连通性
3. 检查 Dubbo 协议端口未被阻止

## 认证问题

### 401 未授权

**症状**：所有 API 请求返回 401。

**可能原因**：

1. **JWT 过期**
   - Token 在配置的 TTL 后过期
   - **解决方案**：重新认证获取新 Token

2. **JWT_KEY 无效**
   - 如果 Token 签发后 `JWT_KEY` 发生变化
   - **解决方案**：清除客户端 Token 并重新登录

3. **缺少 Authorization 头**
   - 请求缺少 `Authorization: Bearer <token>`
   - **解决方案**：在所有认证请求中包含该头

### 403 禁止访问

**症状**：用户已认证但访问被拒绝。

**可能原因**：

1. **权限不足**
   - 用户缺少所需的角色/权限
   - **解决方案**：检查用户的角色分配

2. **资源所有权**
   - 尝试访问其他用户的资源
   - **解决方案**：验证资源所有权或管理员权限

## 存储问题

### 文件上传失败

**症状**：上传返回错误或超时。

**可能原因**：

1. **S3 节点离线**
   ```bash
   # 检查节点状态
   curl http://localhost:8092/actuator/health
   ```
   **解决方案**：重启离线节点或等待故障转移。

2. **副本数不足**
   - 健康节点数无法满足有效仲裁或降级写入最小副本要求
   - **解决方案**：确保在线节点满足 `storage.replication.quorum`，并且不低于 `storage.degraded-write.min-replicas`。

3. **存储桶不存在**
   ```
   Error: The specified bucket does not exist
   ```
   **解决方案**：创建存储桶或检查存储桶名称配置。

### 文件下载失败

**症状**：下载返回 404 或数据损坏。

**可能原因**：

1. **文件未完全复制**
   - Saga 事务未完成
   - **解决方案**：检查数据库中的 Saga 状态。

2. **分片损坏**
   - 一个或多个分片已损坏
   - **解决方案**：通过管理端点触发一致性修复。

3. **包含分片的节点离线**
   - **解决方案**：等待节点恢复或触发重平衡。

### Saga 事务卡住

**症状**：上传无限期显示为挂起状态。

**解决方案**：
1. 检查 `file_saga` 表中的状态
2. 查看 `file_saga_step` 中的步骤失败情况
3. 如需手动触发补偿：
   ```sql
   UPDATE file_saga SET status = 'COMPENSATING'
   WHERE saga_id = '<id>' AND status = 'RUNNING';
   ```

## 区块链问题

### 连接超时

**症状**：区块链操作在 30 秒后超时。

**可能原因**：

1. **FISCO 节点不可达**
   ```bash
   # 测试连通性
   telnet 127.0.0.1 20200
   ```
   **解决方案**：验证节点正在运行且网络允许连接。

2. **证书不匹配**
   - SDK 证书与节点证书不匹配
   - **解决方案**：重新生成并部署匹配的证书。

3. **熔断器打开**
   - 过多失败触发了熔断器
   - **解决方案**：等待半开状态或重启服务。

### 合约调用失败

**症状**：智能合约操作返回错误。

**可能原因**：

1. **合约未部署**
   ```
   Error: Contract address is null
   ```
   **解决方案**：部署合约并在配置中更新地址。

2. **Gas 不足**
   - 交易超过 Gas 限制
   - **解决方案**：在配置中增加 Gas 限制。

3. **权限被拒绝**
   - 调用者未获得合约授权
   - **解决方案**：检查合约 ACL 配置。

## 性能问题

### API 响应缓慢

**症状**：API 请求耗时超过 5 秒。

**诊断步骤**：

1. **检查数据库查询**
   ```bash
   # 启用慢查询日志
   SET GLOBAL slow_query_log = 'ON';
   SET GLOBAL long_query_time = 1;
   ```

2. **检查连接池**
   - Druid 监控：`/record-platform/druid/`
   - 查看连接等待时间

3. **检查 S3 节点延迟**
   - 查看 `s3_node_load_score` 指标
   - 考虑添加更多节点

### 内存使用率高

**症状**：JVM 堆使用率超过 80%。

**解决方案**：

1. **增加堆大小**
   ```bash
   JAVA_OPTS="-Xms4g -Xmx8g"
   ```

2. **检查内存泄漏**
   - 启用 OOM 时的堆转储
   - 使用 Eclipse MAT 或 VisualVM 分析

3. **减少并发上传**
   - 限制 `multipart.max-file-size`
   - 减小分块缓冲区大小

### 数据库连接池耗尽

**症状**："Cannot acquire connection from pool" 错误。

**解决方案**：

1. **增加连接池大小**
   ```yaml
   spring:
     datasource:
       druid:
         max-active: 100
   ```

2. **修复连接泄漏**
   - 检查代码中未关闭的连接
   - 在 Druid 中启用连接泄漏检测

3. **优化慢查询**
   - 添加缺失的索引
   - 检查查询执行计划

## Redis 问题

### 缓存命中率低

**症状**：缓存数据仍查询数据库。

**解决方案**：

1. **检查 Redis 连通性**
   ```bash
   redis-cli -h <redis-host> ping
   ```

2. **验证 TTL 设置**
   - 确保缓存过期时间不会太短

3. **检查内存淘汰**
   - 如果 Redis 因内存压力淘汰键
   - 增加 `maxmemory` 或减少缓存数据

### Redis 连接超时

**症状**：间歇性 Redis 超时。

**解决方案**：

1. **检查网络延迟**
   ```bash
   redis-cli -h <redis-host> --latency
   ```

2. **增加连接超时**
   ```yaml
   spring:
     redis:
       timeout: 5000
   ```

3. **使用连接池**
   - 配置 Lettuce 连接池设置

## RabbitMQ 问题

### 消息未被消费

**症状**：队列深度持续增长。

**解决方案**：

1. **检查消费者是否运行**
   ```bash
   rabbitmqctl list_consumers
   ```

2. **检查消费者错误**
   - 查看应用日志中的监听器异常

3. **检查队列绑定**
   ```bash
   rabbitmqctl list_bindings
   ```

### 死信队列增长

**症状**：DLQ 中有大量消息。

**解决方案**：

1. **查看消息处理错误**
   - 检查应用日志找出根本原因

2. **重新处理 DLQ 消息**
   - 修复问题后将消息移回主队列

3. **调整重试设置**
   ```yaml
   spring:
     rabbitmq:
       listener:
         simple:
           retry:
             max-attempts: 5
   ```
