# 内存与有界传输调优指南

> **适用范围**：当前 `main` 的直接分片上传、Manifest 下载与存储晋升链路
>
> **目标读者**：运维、SRE、容量规划人员
>
> **最后更新**：2026-07-27

## 结论先行

当前大文件主链路不再按“并发数 × 文件大小”聚合堆内存：

- 浏览器将上传分片直接 PUT 到对象存储；backend 处理会话、配额、manifest 和存证元数据。
- `platform-storage` 在同端点使用服务端 copy，在跨端点使用固定缓冲区流式校验/转发。
- 浏览器下载按 manifest 顺序进行有界读取；64 MiB 是内存 sink 的硬上限，更大文件必须写入 File System Access API 流式 sink。
- 文件总大小仍影响传输时长、磁盘空间和预签名 URL 生命周期，但不应再直接代入 JVM 堆公式。

旧的后端代理分片接口仍是兼容链路，其容量模型与直接链路不同。只有确认流量确实经过兼容接口时，才按旧分片大小单独压测和扩容，不能把两种口径混在一起。

## 固定边界

### 直接上传与存储晋升

| 边界 | 默认值 | 说明 |
| --- | ---: | --- |
| 单文件 | 4 GiB | `storage.direct-upload.max-file-size-bytes` |
| 单分片 | 100 MiB | `storage.direct-upload.max-part-size-bytes` |
| 跨端点流式缓冲 | 64 KiB | 有效范围 8 KiB–1 MiB |
| 一组副本晋升超时 | 300 秒 | 最大 1,800 秒 |
| 分片锁等待 | 5 秒 | 最大 60 秒 |
| staging 保留 | 48 小时 | 有效最小值 48 小时 |
| 清理批量 | 200 | 使用 600 秒 fencing claim lease |

同端点 copy 不在 Java 堆中缓存完整对象。跨端点转发为每个活动流分配固定应用缓冲，同时 AWS SDK、TLS、HTTP 客户端和线程栈还有额外内存；规划时必须通过真实 RSS/Native Memory Tracking 校准这些非堆开销。

### 浏览器下载

| 边界 | 值 | 行为 |
| --- | ---: | --- |
| 内存 sink 硬上限 | 64 MiB | 仅在总输出不超过上限时构造 `Blob` |
| 建议流式提示 | 500 MiB | 提示级别，不放宽 64 MiB 上限 |
| 超大文件提示 | 2 GiB | 仍要求流式 sink |
| 绝对下载上限 | 100 GiB | 超过即拒绝 |
| 单网络响应块 | 1 MiB | 超限立即失败 |
| 历史密文分片 | 约 80 MiB + 4 KiB | legacy v1 上限 |
| 单文件分片数 | 10,000 | 超限立即失败 |
| 获取尝试 | 3 次 | 401/403 刷新 metadata；仅 5xx 有限重试 |

下载支持 `NONE`、legacy v1 AEAD 和 framed AEAD v2。所有格式都必须验证长度、顺序和摘要；取消、解密、哈希、网络或写入失败会中止 sink，不能留下“成功”文件。

## 容量模型

### Backend

直接上传与 manifest 下载的 backend 增量堆主要来自请求 DTO、canonical JSON、数据库对象、RPC 元数据和并发控制，不来自文件正文：

```text
backend 目标堆 = 空闲稳定堆
               + 峰值并发请求 × 实测每请求元数据增量
               + proof/manifest/查询 worker 的实测工作集
               + GC 安全余量
```

不要使用“并发上传 × 分片大小 × 2”公式。若路由仍允许兼容的 backend-proxied chunk upload，应单独记录该路由的并发量和分片驻留，并设置独立限流。

### Storage

跨端点 direct-upload 晋升的可见应用缓冲近似为：

```text
应用流缓冲 ≈ 活动跨端点传输 lane × effectiveStreamBufferBytes
storage 进程预算 = 空闲稳定 RSS
                 + 应用流缓冲
                 + SDK/TLS/direct-buffer/线程栈实测增量
                 + repair 与 cleanup 并发实测增量
                 + 安全余量
```

这个公式只描述由代码明确控制的缓冲，不代表完整 RSS。副本数会增加 provider 请求、连接和传输工作量；是否同时占用内存取决于实际 promotion lane，必须用目标拓扑压测。

### Browser

```text
内存 sink：单任务输出上限 64 MiB + 网络/解密/Blob 开销
流式 sink：有界网络块 + 格式解密状态 + 浏览器/文件系统缓冲
```

不要因为设备内存较大而提高 64 MiB 硬上限。File System Access API 不可用时，大于 64 MiB 的自有、公开分享和认证分享文件都会在 Blob/Base64 物化前失败闭合；后端代理不是无界兜底。

## 调优步骤

1. 在目标 JVM 参数、容器限制、对象存储拓扑和 TLS 配置下记录空闲基线。
2. 分别运行同端点 copy、跨端点 stream、降级 repair、abort/cleanup，不把不同路径合并为一个平均值。
3. 逐级提高并发，记录 heap、non-heap、direct buffer、RSS、线程、GC 暂停、连接池和 provider 延迟。
4. 以 p99、错误率、残留 receipt/tombstone/repair 状态和资源回落共同判断稳定点。
5. 在稳定点以下保留容量余量，再配置入口并发、线程池、连接池和容器 memory limit。
6. 变更缓冲区、超时、副本拓扑或 SDK 后重新建立基线；不同 fingerprint 的结果不能直接比较。

P2-4 的 exact-main 负载烟测使用 Linux amd64、Java 21.0.11、4 processors，4 并发完成 8/8 次迭代；256 KiB 负载下 wall time 526 ms、p99 381 ms、吞吐约 3.98 MiB/s，heap delta 16 MiB、direct-buffer delta 3,080,192 bytes、thread delta 23，receipt/tombstone 从 8/8 回落到 0/0，最终无残留。该结果只证明对应 fingerprint 与小负载冒烟门槛，不是生产容量承诺。完整证据见[交付证据矩阵](/zh/architecture/delivery-evidence)。

## JVM 与容器配置

以下模板只提供诊断能力，不代表固定生产规格。`-Xmx` 必须来自目标环境压测，并低于容器 memory limit，为 metaspace、direct buffer、线程栈、代码缓存和 libc 保留空间。

```bash
JAVA_OPTS="-XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/record-platform/heapdump.hprof \
  -Xlog:gc*:file=/var/log/record-platform/gc.log:time,level,tags:filecount=10,filesize=20m"
```

诊断 non-heap 漂移时可在受控环境启用 Native Memory Tracking：

```bash
JAVA_OPTS="$JAVA_OPTS -XX:NativeMemoryTracking=summary"
jcmd <pid> VM.native_memory baseline
jcmd <pid> VM.native_memory summary.diff
```

NMT 和详细 GC 日志有额外成本，应先在预生产验证，再决定生产采样窗口。

## 监控与告警

至少按服务和实例监控：

- JVM heap/non-heap used、committed、max；
- process RSS、容器 working set 与 OOM kill；
- direct/mapped buffer pool 数量与已用字节；
- live/peak thread、连接池 pending/acquired、GC pause 与 allocation rate；
- `storage_direct_upload_operations_total`；
- `storage_direct_upload_transfers_total`；
- `storage_direct_upload_staging_cleanup_total`；
- staging lifecycle、promotion receipt、repair、tombstone 的待处理和超龄数量；
- 浏览器端峰值 buffer、sink 类型、重试数、metadata refresh 与失败原因。

建议以持续时间告警代替单点尖峰：例如 heap 使用率持续高位并伴随 old-gen 回收后不回落、RSS 接近容器限制、direct buffer 单调增长、staging 超过保留窗口或 receipt/tombstone 不归零。阈值应来自容量测试，而非复制通用百分比。

## 故障定位

### Heap 高但 RSS 同步高

1. 确认是否仍有流量进入 backend-proxied 兼容上传；下载侧则确认实际使用的是 64 MiB 内存 sink 还是 File System Access 流式 sink，不存在无界后端代理下载兜底。
2. 按 class histogram 检查大 `byte[]`、JSON/manifest、proof ZIP 或缓存对象。
3. 对照 GC 后存活集；只在有泄漏证据时采集 heap dump。

### Heap 正常但 RSS 持续升高

1. 查看 direct/mapped buffer pool 与 NMT 的 `Thread`、`Arena Chunk`、`Internal`、`Class`。
2. 检查 HTTP/S3 连接是否归还、线程是否回落、取消路径是否关闭 response body 与 sink。
3. 对照 `storage_direct_upload_transfers_total` 的结果标签与超时，定位 provider 阻塞。

### staging 或生命周期记录不回落

1. 检查 cleanup 是否启用、claim lease 是否过期、分片锁是否长期占用。
2. 核对 complete/abort 的 receipt、operation intent、tombstone 与 fencing generation。
3. 不要手工批量删除 `staging/direct-upload`；先通过 reference census 和专用生命周期流程确认所有权。
4. provider 404 属于幂等完成；其他异常应保留 lifecycle 记录重试。

### 浏览器大文件失败

1. 确认浏览器同时支持 File System Access API 和 Streams。
2. 检查是否超过 100 GiB、10,000 parts、1 MiB 网络块或 legacy part 上限。
3. 401/403 应刷新 metadata，不能原 URL 循环重试；5xx 最多三次尝试。
4. 校验失败后确认 sink 已 abort，避免把部分文件误认为成功。

## 变更验收清单

- [ ] 目标路径已确认是 direct、legacy proxy、repair 或 cleanup 中的哪一种
- [ ] 同端点与跨端点场景分别压测
- [ ] 记录 commit、配置、镜像、JDK、CPU、内存、对象存储和网络 fingerprint
- [ ] 同时保存 heap、RSS、direct buffer、线程、GC 与 lifecycle 证据
- [ ] p99、错误率、资源回落和零残留均满足门槛
- [ ] OOM/取消/provider timeout 后 sink、response、lock、claim 可恢复
- [ ] 未通过增大堆掩盖无界聚合或资源泄漏

相关文档：

- [系统架构](/zh/architecture/system-overview)
- [分布式存储](/zh/architecture/distributed-storage)
- [分片 Manifest 与历史数据治理](/zh/architecture/chunk-manifest)
- [K6 负载测试](/zh/perf/k6-loadtest)
- [交付证据矩阵](/zh/architecture/delivery-evidence)
