# 内存调优指南

> **适用版本**: v1.x  
> **目标读者**: 运维团队、SRE  
> **最后更新**: 2026-06-14

---

## 概述

RecordPlatform 后端服务在文件上传/下载链路中会临时加载分片数据到堆内存。本文档提供内存容量规划、监控配置和故障处理指南。

### 当前架构特点

- **上传链路**: 后端读取本地临时分片文件 → 加载到内存 → 通过 Dubbo 传输到存储服务
- **下载链路**: 存储服务从 S3 读取对象 → 加载到内存 → 返回给后端 → 前端
- **单分片上限**: 80MB（受 Dubbo 100MB 消息体限制）
- **内存聚合点**: FileSagaOrchestrator + DistributedStorageServiceImpl

### v2.0 改进计划

参见 `ROADMAP.md` P2 任务（第 7-10 周）：
- 前端使用 S3 Multipart Upload 直接上传到对象存储
- 前端使用预签名 URL 直接下载并流式解密
- 后端仅处理元数据和区块链存证，完全消除数据流代理

---

## JVM 堆内存配置

### 推荐配置（生产环境）

#### backend-web（主业务服务）
```bash
JAVA_OPTS="-Xms2g -Xmx4g \
  -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/record-platform/heapdump.hprof"
```

**说明**:
- 初始堆 2GB，最大 4GB（根据并发量调整）
- G1 GC 适合中大型堆，目标 GC 暂停 <200ms
- OOM 时自动生成堆转储文件便于分析

#### platform-storage（存储服务）
```bash
JAVA_OPTS="-Xms1g -Xmx2g \
  -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/record-platform/storage-heapdump.hprof"
```

**说明**:
- 存储服务主要处理下载流量
- 2GB 堆足够处理中等并发（~20 并发下载）

#### platform-fisco（区块链服务）
```bash
JAVA_OPTS="-Xms512m -Xmx1g \
  -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
  -XX:+UseG1GC"
```

**说明**:
- 区块链服务内存占用较小
- 主要处理 RPC 调用和 Solidity 合约交互

### 内存容量公式

#### 上传场景
```
backend heap ≥ 并发上传数 × 平均分片大小 × 2 + 基础开销(1GB)
```

**示例**:
- 20 并发上传，平均分片 60MB
- 所需堆内存: `20 × 60MB × 2 + 1GB = 3.4GB`
- **推荐配置**: 4GB

#### 下载场景
```
storage heap ≥ 并发下载数 × 平均文件大小 + 基础开销(512MB)
```

**示例**:
- 15 并发下载，平均文件 40MB
- 所需堆内存: `15 × 40MB + 512MB = 1.1GB`
- **推荐配置**: 2GB

---

## 并发容量规划

### 容量规划表

| 部署规模 | 并发上传数 | 并发下载数 | backend heap | storage heap | 预期 GC 频率 |
|----------|-----------|-----------|--------------|--------------|--------------|
| 小型     | ≤10       | ≤10       | 2GB          | 1GB          | 1-2次/小时   |
| 中型     | ≤30       | ≤20       | 4GB          | 2GB          | 3-5次/小时   |
| 大型     | ≤50       | ≤30       | 6GB          | 3GB          | 5-10次/小时  |

### 调优建议

#### 小型部署（<50 用户）
- backend: 2GB 堆
- storage: 1GB 堆
- 适合演示环境、开发团队内部使用

#### 中型部署（50-200 用户）
- backend: 4GB 堆（推荐配置）
- storage: 2GB 堆
- 适合企业内部文档管理、部门级应用

#### 大型部署（>200 用户）
- backend: 6GB+ 堆
- storage: 3GB+ 堆
- 需要配置并发限流（见下文）
- 建议规划 v2.0 架构升级

---

## 监控配置

### 关键指标

#### JVM 堆内存指标
```
jvm_memory_used_bytes{area="heap"}           # 堆内存使用量
jvm_memory_max_bytes{area="heap"}            # 堆内存上限
jvm_memory_committed_bytes{area="heap"}      # 已提交堆内存
```

#### GC 指标
```
jvm_gc_pause_seconds_sum                     # GC 暂停总时间
jvm_gc_pause_seconds_count                   # GC 次数
jvm_gc_memory_allocated_bytes_total          # 内存分配速率
```

#### 应用指标
```
upload_sessions_active                       # 当前活跃上传会话数
download_concurrent_requests                 # 并发下载请求数
saga_active_count                           # 活跃 Saga 数量
```

### Prometheus 告警规则

创建 `monitoring/prometheus/alerts/memory-alerts.yml`:

```yaml
groups:
  - name: memory_alerts
    interval: 30s
    rules:
      # 堆内存使用率告警
      - alert: BackendHeapMemoryHigh
        expr: |
          (jvm_memory_used_bytes{application="backend-web", area="heap"} 
           / jvm_memory_max_bytes{application="backend-web", area="heap"}) > 0.85
        for: 5m
        labels:
          severity: warning
          component: backend
        annotations:
          summary: "后端堆内存使用率超过 85%"
          description: "当前使用率 {{ $value | humanizePercentage }}，可能因高并发上传导致。建议检查活跃上传会话数。"

      # GC 暂停时间告警
      - alert: BackendGCPauseTooLong
        expr: |
          rate(jvm_gc_pause_seconds_sum{application="backend-web"}[5m]) > 1
        for: 3m
        labels:
          severity: warning
          component: backend
        annotations:
          summary: "后端 GC 暂停时间过长"
          description: "过去 5 分钟平均 GC 暂停超过 1 秒，影响请求响应时间。"

      # 并发上传数告警
      - alert: HighConcurrentUploads
        expr: upload_sessions_active > 40
        for: 10m
        labels:
          severity: info
          component: backend
        annotations:
          summary: "并发上传数接近上限"
          description: "当前 {{ $value }} 个活跃上传会话，接近推荐上限 50。"

      # OOM 风险预警
      - alert: BackendOOMRisk
        expr: |
          (jvm_memory_used_bytes{application="backend-web", area="heap"} 
           / jvm_memory_max_bytes{application="backend-web", area="heap"}) > 0.95
        for: 2m
        labels:
          severity: critical
          component: backend
        annotations:
          summary: "后端即将 OOM"
          description: "堆内存使用率 {{ $value | humanizePercentage }}，立即检查并发上传数。"
```

### Grafana 仪表板

关键图表：
1. **堆内存使用趋势**（折线图）
2. **GC 频率和暂停时间**（柱状图）
3. **活跃上传/下载会话数**（面积图）
4. **内存分配速率**（速率图）

---

## 故障处理

### OOM 场景处理

#### 症状
- 应用突然终止，日志中有 `java.lang.OutOfMemoryError: Java heap space`
- 响应时间显著增加
- GC 日志显示 Full GC 频繁发生

#### 应急措施

1. **立即检查并发量**
   ```bash
   # 查看活跃上传会话数
   curl http://localhost:8000/record-platform/actuator/metrics/upload.sessions.active
   
   # 查看堆内存使用情况
   jstat -gc <pid> 1000
   ```

2. **降低并发上限**（临时措施）
   - 通过 Nacos 动态配置降低 `MAX_CONCURRENT_UPLOADS`
   - 或重启服务并调整环境变量

3. **重启服务释放内存**
   ```bash
   ./scripts/start.sh restart backend-web
   ```

4. **分析堆转储文件**
   ```bash
   # 使用 Eclipse MAT 或 jhat 分析
   jhat /var/log/record-platform/heapdump.hprof
   # 或上传到 https://heaphero.io 在线分析
   ```

#### 根因分析

常见原因：
- 并发上传数超过容量规划
- 用户上传超大文件（接近 4GB 上限）
- Saga 状态未正确清理导致内存泄漏
- FileUploadState 缓存过期策略失效

### 内存泄漏排查

#### 1. 生成堆快照
```bash
# 方法一：使用 jmap
jmap -dump:live,format=b,file=heap.bin <pid>

# 方法二：通过 actuator（如果启用）
curl -X POST http://localhost:8000/record-platform/actuator/heapdump -o heapdump.hprof
```

#### 2. 使用 MAT 分析

关键检查点：
- **Dominator Tree**: 查找占用内存最多的对象
- **Histogram**: 统计对象实例数量
- **Leak Suspects**: 自动识别疑似泄漏

重点关注类：
- `cn.flying.entity.saga.FileSaga`
- `cn.flying.entity.FileUploadState`
- `byte[]` 数组（分片数据）
- `LinkedHashMap`（Saga payload context）

#### 3. 检查代码逻辑

```bash
# 检查 Saga 清理逻辑
grep -r "deleteSaga\|removeSaga" platform-backend/backend-service/src/

# 检查 FileUploadState 过期清理
grep -r "@Scheduled.*cleanUp" platform-backend/backend-service/src/
```

### 性能下降处理

#### 症状
- 上传/下载响应时间变慢
- CPU 使用率升高
- GC 暂停时间增加

#### 诊断步骤

1. **检查 GC 日志**
   ```bash
   # 启用 GC 日志（在 JAVA_OPTS 中添加）
   -Xlog:gc*:file=/var/log/record-platform/gc.log:time,uptime,level,tags
   
   # 分析 GC 日志
   java -jar gceasy.jar /var/log/record-platform/gc.log
   ```

2. **检查线程状态**
   ```bash
   # 生成线程转储
   jstack <pid> > thread-dump.txt
   
   # 查找阻塞线程
   grep -A 10 "BLOCKED\|WAITING" thread-dump.txt
   ```

3. **检查数据库连接池**
   ```bash
   # Druid 监控页面
   curl http://localhost:8000/record-platform/druid/sql.html
   ```

#### 调优措施

- 调整 G1 GC 参数：`-XX:MaxGCPauseMillis=100`（降低目标暂停时间）
- 增加堆内存：`-Xmx6g`
- 启用并发限流（见下文）
- 考虑水平扩展（增加实例数）

---

## 并发限流配置

### Redis 分布式计数器

在 `FileUploadServiceImpl` 中实现：

```java
private static final String UPLOAD_CONCURRENCY_KEY = "upload:concurrency:global";
private static final int MAX_CONCURRENT_UPLOADS = 50;  // 通过 @Value 注入

@Override
public String startUploadSession(Long userId, String fileName, long fileSize) {
    // 检查全局并发数
    Long currentConcurrency = redisTemplate.opsForValue().increment(UPLOAD_CONCURRENCY_KEY, 0);
    if (currentConcurrency != null && currentConcurrency >= MAX_CONCURRENT_UPLOADS) {
        log.warn("上传并发数达到上限: current={}, max={}", currentConcurrency, MAX_CONCURRENT_UPLOADS);
        throw new GeneralException(ResultEnum.SYSTEM_BUSY, "系统繁忙，请稍后重试");
    }
    
    // 创建会话时递增
    redisTemplate.opsForValue().increment(UPLOAD_CONCURRENCY_KEY);
    redisTemplate.expire(UPLOAD_CONCURRENCY_KEY, 1, TimeUnit.HOURS);
    
    // ... 原有逻辑
}

@Override
public void completeUpload(String sessionId) {
    // 完成时递减
    redisTemplate.opsForValue().decrement(UPLOAD_CONCURRENCY_KEY);
    // ... 原有逻辑
}
```

### Resilience4j 速率限制

在 `application.yml` 配置：

```yaml
resilience4j:
  ratelimiter:
    configs:
      default:
        register-health-indicator: true
        event-consumer-buffer-size: 100
    instances:
      uploadRateLimiter:
        limit-for-period: 10        # 10 次请求
        limit-refresh-period: 60s   # 每 60 秒
        timeout-duration: 5s        # 超时等待 5 秒
      downloadRateLimiter:
        limit-for-period: 20
        limit-refresh-period: 60s
        timeout-duration: 3s
```

应用到服务方法：

```java
@RateLimiter(name = "uploadRateLimiter", fallbackMethod = "uploadRateLimitFallback")
public String startUploadSession(Long userId, String fileName, long fileSize) {
    // ... 原有逻辑
}

private String uploadRateLimitFallback(Long userId, String fileName, long fileSize, Exception e) {
    throw new GeneralException(ResultEnum.RATE_LIMIT_EXCEEDED, 
        "上传请求过于频繁，请稍后重试");
}
```

---

## 监控数据示例

### 正常运行状态

```
jvm_memory_used_bytes{area="heap"}     = 1.2GB / 4GB (30%)
jvm_gc_pause_seconds_sum (1h)         = 15s (平均 250ms/次)
upload_sessions_active                = 8
download_concurrent_requests          = 5
```

### 高负载状态（需关注）

```
jvm_memory_used_bytes{area="heap"}     = 3.2GB / 4GB (80%)
jvm_gc_pause_seconds_sum (1h)         = 120s (平均 500ms/次)
upload_sessions_active                = 42
download_concurrent_requests          = 18
```

**建议操作**: 监控趋势，准备扩容或限流

### OOM 风险状态（需立即处理）

```
jvm_memory_used_bytes{area="heap"}     = 3.8GB / 4GB (95%)
jvm_gc_pause_seconds_sum (5m)         = 45s (频繁 Full GC)
upload_sessions_active                = 58
download_concurrent_requests          = 25
```

**立即操作**: 降低并发上限，重启服务，扩容

---

## 附录

### 相关文档

- `ROADMAP.md` - P2 任务：S3 直传架构迁移
- `CONTRIBUTING.md` - 编码规范和架构约束
- `docs/architecture/saga-pattern.md` - Saga 补偿机制
- `docs/deployment/production-checklist.md` - 生产环境检查清单

### 代码位置

- 上传内存聚合: `platform-backend/backend-service/src/main/java/cn/flying/service/saga/FileSagaOrchestrator.java:148`
- 下载内存聚合: `platform-storage/src/main/java/cn/flying/storage/service/DistributedStorageServiceImpl.java:704`

### 工具推荐

- **MAT (Memory Analyzer Tool)**: Eclipse 堆分析工具
- **GCeasy**: 在线 GC 日志分析 https://gceasy.io
- **HeapHero**: 在线堆转储分析 https://heaphero.io
- **VisualVM**: JVM 监控和性能分析
- **Arthas**: 阿里开源的 Java 诊断工具

---

**维护者**: 运维团队  
**审核者**: 架构组  
**下次审查**: v2.0 架构迁移前（预计 Week 7-10）
