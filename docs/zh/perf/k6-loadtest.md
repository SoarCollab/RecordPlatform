# k6 负载测试与直传证据

仓库提供可重复、可门禁、可归档的 k6 场景，覆盖查询、后端代理分片上传、混合流量和对象存储直传闭环。

## 范围与门禁边界

| 场景 | 覆盖链路 |
| --- | --- |
| `file-query` | 基础、关键字、组合条件文件查询及文件统计 |
| `chunk-upload` | 创建旧式上传会话、上传分片、完成、查询进度、清理 |
| `core-mixed` | 默认 70% 查询、30% 分片上传 |
| `direct-path` | 直传创建 → 原始预签名 PUT → 完成 → manifest metadata → 原始预签名 GET → 大小/哈希校验 → 清理 |
| `all` | 按 profile 组合场景 |

`smoke/all` 执行 `file-query + core-mixed`；`load/all` 执行 `file-query + chunk-upload + direct-path`。

外部环境性能工作流仅手工触发，不是 PR 强制检查。Pull request 的真实 MinIO/Redis/Toxiproxy 门禁来自 `platform-storage -Pit`；不能把手工 k6 运行描述为 PR 门禁。

`Required CI` 另在禁用网络的固定镜像中初始化全部七个 k6 入口及其受支持场景，然后执行本地 fixture 断言。这是运行器/fixture 门禁，不是部署环境 smoke 测试。

## 前置条件

- Backend 可通过 `BASE_URL` 访问，默认 `http://localhost:8000/record-platform/api/v1`。
- 显式设置 `TENANT_ID`、`USERNAME`、`PASSWORD`；登录请求也必须携带 `X-Tenant-ID`。
- 使用校验过的 k6 **0.57.0** 发布二进制，或显式选择 Docker engine。这是已验证的兼容性基线，不宣称是最新版或长期维护版；更新版本需单独验证兼容性。

`--engine auto` 只会选择本地 k6。Docker 执行要求 `--engine docker` 和 digest 固定的 `K6_DOCKER_IMAGE`。工作流固定为：

```text
grafana/k6@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b
```

原 0.49.0 默认解析器不支持对象展开，`base` 模式也不能解析当前导入图。[k6 0.57.0](https://github.com/grafana/k6/releases/tag/v0.57.0) 不需要实验兼容参数即可使用现代语法。`tools/k6/runtime.env` 是规范 pin；本地二进制必须先与官方 release checksum 文件核对，再将其目录加入 `PATH`。

```bash
# 不访问目标、不登录、不上传、不清理：
bash tools/k6/check-runtime.sh docker
# 用匹配且已校验的发布二进制执行同一运行器检查：
K6_BINARY=/path/to/k6-v0.57.0/k6 bash tools/k6/check-runtime.sh local
```

### 私有 HTTPS 与用户自行验收

原生 k6 使用本机系统信任库，应导入签发 CA 或明确批准的自签**公共**证书。Docker 不继承 macOS 钥匙串：将 `K6_CA_CERT_FILE` 设置为可读的本地 PEM 信任包，两个包装脚本会只读挂载并设置容器 `SSL_CERT_FILE`。证书必须覆盖 API 和预签名对象存储主机；不得传入私钥或关闭 TLS 校验。凭证、证书和结果产物均保留在 Git 外。

部署目标 `main` 版本后，私下导出一次性测试账号的 `BASE_URL`、`TENANT_ID`、`USERNAME`、`PASSWORD`，再执行：

```bash
source tools/k6/runtime.env
export K6_DOCKER_IMAGE="$K6_TESTED_IMAGE"
export K6_CA_CERT_FILE=/private/path/public-trust-bundle.pem
export VUS=1 DURATION=15s DIRECT_TOTAL_CHUNKS=2 DIRECT_CHUNK_SIZE=65536 CLEANUP=true
bash tools/k6/run-local.sh --profile smoke --scenario direct-path --engine docker \
  --run-id "acceptance-$(date +%Y%m%d%H%M%S)"
```

要求退出码为零，completed-file/flow/cleanup 样本非零，大小/SHA-256 检查全部通过，flow/cleanup 失败率为零。如需链级验收，另行核验链回执：本套件验证文件/manifest/对象生命周期，不独立查询链 RPC 回执。cleanup 表示应用逻辑删除，物理保留/清扫仍遵循服务器策略。仅初始化成功不能证明部署环境验收完成。

## 运行档位

```bash
# 日常查询与混合 smoke 回归
bash tools/k6/run-local.sh --profile smoke --scenario all --engine auto

# 查询、旧式上传与直传 load 档位
bash tools/k6/run-local.sh --profile load --scenario all --engine auto

# 只采集直传闭环证据
bash tools/k6/run-local.sh --profile smoke --scenario direct-path --engine auto
```

支持值：

- `K6_PROFILE=smoke|load`
- `K6_SCENARIO=all|file-query|chunk-upload|core-mixed|direct-path`
- `K6_ENGINE=auto|local|docker`

`core-mixed` 仅支持 smoke；load 支持 `all|file-query|chunk-upload|direct-path`。

手工 `.github/workflows/perf-smoke.yml` 默认执行 `direct-path/smoke`，可配置 profile、scenario、concurrency、duration、environment fingerprint、baseline path、resource snapshot path 和 lifecycle snapshot path。

## 直传闭环合同

每次 direct 迭代执行完整生命周期：

1. 创建直传会话并校验 canonical part plan。
2. 将确定性字节 PUT 到每个预签名 staging URL。
3. 用 ETag 与 `sha256:<小写十六进制>` 证据完成会话。
4. 获取基于 manifest 的下载 metadata。
5. GET 每个预签名对象，校验响应大小、分片哈希、总大小和完整文件哈希。
6. 删除创建的文件并验证清理证据。

原始预签名 PUT/GET 不能携带平台 `Authorization`、`X-Tenant-ID` 或 JSON header。ETag 只用于对象版本条件，SHA-256 才是内容身份。direct suite 会禁用 k6 的 `url`/`name` 系统标签，并强制 `--log-output none`，防止签名查询参数进入指标、日志、失败样本或 artifact。

两个上传路径都使用 `.txt` / `text/plain` 和真正的可打印 ASCII/换行 `ArrayBuffer`，不是把随机二进制改后缀。共享 fixture 保持配置的精确分片字节数，并由 run、VU、iteration、part 身份生成确定性内容，避免跨运行意外去重；生产扩展名/MIME 校验不变。每次运行使用新的 `RUN_ID`，重复同一 ID 会有意复用相同内容。

## 门槛

全局门槛：

- `http_req_failed < 1%`
- `checks > 99%`

查询门槛：

- `files_basic p95 < 800 ms`
- `files_keyword p95 < 800 ms`
- `files_combo p95 < 1,000 ms`
- `files_stats p95 < 800 ms`

旧式上传门槛：

- `upload_start p95 < 1,200 ms`
- `upload_chunk p95 < 1,500 ms`
- `upload_complete p95 < 1,500 ms`
- `upload_e2e_ms p95 < 6,000 ms`

直传门槛：

- `direct_flow_failure_rate == 0`
- `direct_cleanup_failure_rate == 0`
- upload、download、end-to-end 的 `p99 < DIRECT_P99_BUDGET_MS`

`DIRECT_P99_BUDGET_MS` 默认 60,000 ms，只是测试级总预算，不是生产 SLA。

## 观测与产物

可选的 `DIRECT_RESOURCE_SNAPSHOT_PATH` 和 `DIRECT_LIFECYCLE_SNAPSHOT_PATH` 会在运行开始、清理完成后分别探测。来源未配置或无法读取时，报告会记录 `unavailable` 和原因，不会伪造 heap、GC、thread、direct-buffer、staging、receipt、degraded 或 repair 的零值。

每个 `RESULT_DIR` 包含：

- `summary.txt`、`summary.json`
- `metrics.json`
- `query-baseline.json`
- `direct-path-baseline.json`
- `direct-path-report.md`
- `run-meta.json`

direct baseline 至少要有一条 flow 样本、一条 cleanup 样本和一个完成文件；仅 setup 或零迭代结果不能成为有效证据。

## 基线比较

只比较 `environment.fingerprint` 与 workload/execution 合同都完全一致的运行：

```bash
node tools/k6/scripts/compare-direct-baseline.mjs \
  --baseline tools/k6/results/<BASE>/direct-path-baseline.json \
  --candidate tools/k6/results/<CANDIDATE>/direct-path-baseline.json \
  --output tools/k6/results/<CANDIDATE>/direct-path-comparison.md
```

默认规则在 p95/p99 变差超过 20%、upload/download 吞吐下降超过 20%，或 flow/cleanup 从零变成非零时失败。Fingerprint、profile、scenario、engine、engine artifact、分片计划、executor、并发、时长或 VU 合同任一不同都会返回 `NOT_COMPARABLE`，不会制造虚假回归结论。

查询 smoke/load 结果可生成一个 Markdown 证据片段：

```bash
node tools/k6/scripts/render-query-baseline.mjs \
  --smoke-dir tools/k6/results/<SMOKE_RUN_ID> \
  --load-dir tools/k6/results/<LOAD_RUN_ID> \
  --output tools/k6/results/query-baseline-snippet.md
```

## 常见问题

- `401`：检查凭证和 token。原始预签名 401/403 通常表示 URL 已过期，应重新获取 metadata。
- 缺少租户标识：平台 API（包括登录）必须带 `X-Tenant-ID`，但原始 signed URL 禁止携带。
- 直传 PUT/GET 失败：确认 MinIO CORS 暴露 ETag，且 signed request 没有附加平台 header。
- cleanup 门槛失败：保留运行 artifact 并按 `RUN_ID` 排查；`CLEANUP=false` 的运行不能算生命周期通过。
- `NOT_COMPARABLE`：对齐 commit、配置、目标、engine、OS/架构、CPU/内存和 k6 镜像 fingerprint 后再判断性能。

变量清单和脚本目录以 [`tools/k6/README.md`](https://github.com/SoarCollab/RecordPlatform/blob/main/tools/k6/README.md) 为准。
