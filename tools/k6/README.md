# K6 压测框架（本地 + CI）

本目录提供可复用、可门禁、可归档的 K6 压测框架，覆盖：
- 读路径：`/files`（`basic/keyword/combo`）与 `/files/stats`
- 写路径：`/upload-sessions`、`/upload-sessions/{clientId}/chunks/{chunkNumber}`、`/upload-sessions/{clientId}/complete`、`/upload-sessions/{clientId}/progress`
- 直传闭环：direct create → 原始预签名 PUT → direct complete → manifest metadata → 原始预签名 GET → size/hash 校验 → 清理
- 混合路径：70% 查询 + 30% 上传

## 目录结构

```text
tools/k6/
├── lib/
│   ├── auth.js
│   ├── assertions.js
│   ├── cleanup.js
│   ├── config.js
│   ├── http.js
│   ├── metrics.js
│   └── summary.js
├── scenarios/
│   └── core-mixed.js
├── suites/
│   ├── ci-smoke.js
│   ├── local-load.js
│   └── local-smoke.js
├── scripts/
│   ├── compare-direct-baseline.mjs
│   └── render-query-baseline.mjs
├── chunk-upload.js
├── direct-path.js
├── file-query.js
├── run-ci.sh
└── run-local.sh
```

## 前置条件

- 后端服务可访问（默认：`http://localhost:8000/record-platform/api/v1`）
- 本地使用已校验的 k6 **0.57.0** 发布二进制（不是最新版/长期维护承诺）；或显式使用 Docker 固定镜像：

```bash
source tools/k6/runtime.env
export K6_DOCKER_IMAGE="$K6_TESTED_IMAGE"
bash tools/k6/check-runtime.sh docker
```

- 如需 Docker 执行，必须显式使用 `--engine docker`，并通过 `K6_DOCKER_IMAGE` 提供 digest 固定镜像，例如 `grafana/k6@sha256:<digest>`。

固定基线是 `grafana/k6@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b`（0.57.0）。原 0.49.0 默认/base 模式都无法初始化现有导入图；不是只改一个对象展开表达式就能修复。完整无网络门禁初始化七个入口及受支持场景，并执行真实 ArrayBuffer/hash fixture 断言；本地等效命令为 `K6_BINARY=/path/to/k6-v0.57.0/k6 bash tools/k6/check-runtime.sh local`，版本不符会失败。

两条上传路径现在使用真正 ASCII 文本 `.txt` / `text/plain`，保留精确分片大小、每个 run/VU/iteration/part 的内容隔离、SHA-256 检查和原始预签名请求边界。不修改生产上传白名单；每次使用新 `RUN_ID`。

私有 HTTPS：原生 k6 使用系统信任；Docker 使用 `K6_CA_CERT_FILE=/private/path/public-trust-bundle.pem`，包装脚本将公共证书只读挂载并设置 `SSL_CERT_FILE`。禁止提交证书/私钥或关闭 TLS 验证。完整用户自行验收步骤见 [中文性能指南](../../docs/zh/perf/k6-loadtest.md#私有-https-与用户自行验收)。初始化不访问服务，不能当作真实业务验收。

> 注意：`X-Tenant-ID` 对 `/api/v1/auth/login` 也必填。

## 环境变量

基础变量：
- `BASE_URL`（默认：`http://localhost:8000/record-platform/api/v1`）
- `TENANT_ID`（默认：`1`）
- `USERNAME`（必填）
- `PASSWORD`（必填）

框架变量：
- `K6_PROFILE=smoke|load`
- `K6_SCENARIO=all|file-query|chunk-upload|core-mixed|direct-path`
- `K6_ENGINE=auto|local|docker`（默认 `auto`；`auto` 仅自动使用本地 k6）
- `K6_DOCKER_IMAGE`（Docker 引擎必填，必须是 digest 固定镜像）
- `RUN_ID`（默认当前时间戳；只允许 1～64 位安全字符，首位字母/数字，其余字母、数字、点、下划线或连字符；非法值会在执行前失败关闭）
- `RESULT_DIR`（默认 `tools/k6/results/<RUN_ID>`）
- `ENVIRONMENT_FINGERPRINT`（同环境 baseline 比较键；脚本默认按脱敏目标、引擎、OS/架构生成）

上传变量：
- `TOTAL_CHUNKS`（默认 `5`）
- `CHUNK_SIZE`（默认 `1024` 字节）

直传变量：
- `DIRECT_TOTAL_CHUNKS`（默认 `2`，未设置时可继承 `TOTAL_CHUNKS`）
- `DIRECT_CHUNK_SIZE`（默认 `65536` 字节，未设置时可继承 `CHUNK_SIZE`）
- `DIRECT_P99_BUDGET_MS`（默认 `60000`，作为测试级总预算，不等同生产 SLA）
- `DIRECT_RESOURCE_SNAPSHOT_PATH`（可选，同源资源快照相对路径；分别在运行开始和清理完成后探测，未配置时两阶段均报告为 `unavailable/not_configured`）
- `DIRECT_LIFECYCLE_SNAPSHOT_PATH`（可选，同源 staging/receipt/degraded/repair 快照相对路径；分别记录开始/结束可用性，未配置时不伪造 `0`）

direct-path 套件禁用 k6 的 `url`/`name` 系统标签并关闭运行日志输出，避免预签名查询参数进入指标、日志或 artifact；平台鉴权头也不会发送到原始预签名 PUT/GET。

可选变量：
- `CLEANUP=true|false`（默认 `true`）
- `CI_INCLUDE_CHUNK=true|false`（默认 `false`）
- `CI_INCLUDE_DIRECT=true|false`（默认 `false`；显式选择 `direct-path` 时自动启用）

## 本地运行

### 1) smoke 档位（推荐日常回归）

默认 `K6_SCENARIO=all` 时只跑 `file-query + core-mixed`。

```bash
bash tools/k6/run-local.sh --profile smoke --scenario all --engine auto
```

### 2) load 档位（查询/上传控速）

默认 `K6_SCENARIO=all` 时跑 `file-query + chunk-upload + direct-path`。

```bash
bash tools/k6/run-local.sh --profile load --scenario all --engine auto
```

### 3) 指定单场景

```bash
bash tools/k6/run-local.sh --profile smoke --scenario file-query --engine auto
bash tools/k6/run-local.sh --profile smoke --scenario core-mixed --engine auto
bash tools/k6/run-local.sh --profile smoke --scenario chunk-upload --engine auto
bash tools/k6/run-local.sh --profile smoke --scenario direct-path --engine auto
bash tools/k6/run-local.sh --profile load --scenario chunk-upload --engine auto
bash tools/k6/run-local.sh --profile load --scenario direct-path --engine auto
```

## 固定门禁阈值

全局：
- `http_req_failed < 1%`
- `checks > 99%`

查询链路：
- `files_basic p95 < 800ms`
- `files_keyword p95 < 800ms`
- `files_combo p95 < 1000ms`
- `files_stats p95 < 800ms`

上传链路：
- `upload_start p95 < 1200ms`
- `upload_chunk p95 < 1500ms`
- `upload_complete p95 < 1500ms`
- `upload_e2e_ms p95 < 6000ms`

直传链路：
- `direct_flow_failure_rate == 0`
- `direct_cleanup_failure_rate == 0`
- upload/download/end-to-end `p99 < DIRECT_P99_BUDGET_MS`

直传 PUT/GET 直接请求预签名 URL，不携带 `Authorization`、`X-Tenant-ID` 或 JSON 头。ETag 只作为对象版本条件；内容身份始终使用 `sha256:<lowercase hex>` 复核。

包含 `direct-path` 时，运行脚本强制使用 `k6 --log-output none`，避免网络错误把含签名查询参数的预签名 URL 写入控制台日志。结果、失败样本和阈值仍通过 `summary.*`、`metrics.json` 与退出码交付。

## 报告产物

每次运行会在 `RESULT_DIR` 输出：
- `summary.txt`：可读摘要（含 endpoint 的 p50/p90/p95/p99、错误率、请求量、阈值结果、失败样本）
- `summary.json`：完整 k6 summary
- `metrics.json`：精简指标快照
- `query-baseline.json`：检索基线结构化快照（endpoint 指标 + 阈值结果，便于文档回填）
- `direct-path-baseline.json`：直传 upload/download/e2e p50/p95/p99、吞吐、失败率和观测可用性
- `direct-path-report.md`：直传报告；未配置的 heap/GC/thread/direct-buffer 或生命周期采集会显式显示 `unavailable`，绝不填 0
- `run-meta.json`：运行元数据（目标掩码、环境指纹、OS/架构、CPU/内存、k6 版本等）

## 基线回填自动汇总

当 smoke/load 都有结果目录后，可使用脚本一键生成 Markdown 回填片段：

```bash
node tools/k6/scripts/render-query-baseline.mjs \
  --smoke-dir tools/k6/results/<SMOKE_RUN_ID> \
  --load-dir tools/k6/results/<LOAD_RUN_ID> \
  --output tools/k6/results/query-baseline-snippet.md
```

脚本会读取两个目录下的 `query-baseline.json`，输出：
- “结果回填模板”表格（RUN_ID / 阈值结论）
- “指标摘录”表格（endpoint 的 p50/p90/p95/errorRate/requests）

## 直传基线比较

只比较 `environment.fingerprint` 完全一致的两份基线。默认规则为：p95/p99 变差超过 20%、上传/下载吞吐下降超过 20%，或 flow/cleanup 从 0 变为非 0 时返回失败。环境不同时输出 `NOT_COMPARABLE`，不制造虚假回归结论。

比较前还会要求 `evidence.valid=true`：至少存在一条 flow 样本、一条 cleanup 样本和一个完成文件。仅加载脚本、setup 失败或没有执行迭代生成的零值不能成为性能基线。

```bash
node tools/k6/scripts/compare-direct-baseline.mjs \
  --baseline tools/k6/results/<BASE>/direct-path-baseline.json \
  --candidate tools/k6/results/<CANDIDATE>/direct-path-baseline.json \
  --output tools/k6/results/<CANDIDATE>/direct-path-comparison.md
```

## CI 说明

- CI 套件入口：`tools/k6/suites/ci-smoke.js`
- 本地脚本入口：`tools/k6/run-ci.sh`
- 工作流：`.github/workflows/perf-smoke.yml`

`run-ci.sh` 必需环境变量（仅支持统一变量名）：
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

可选参数：
- `--engine auto|local|docker`（默认 `auto`）

GitHub Actions Secrets（与统一变量同名）：
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

手工工作流默认执行 `direct-path/smoke`，也可选择 load 和其他场景（`core-mixed` 仅支持 smoke）。镜像固定为 `grafana/k6:0.57.0` 对应的 multi-arch digest；报告 artifact 使用 `if: always()` 上传。外部环境 secret 只服务手工工作流，不属于 PR 强制门禁；PR 内真实 MinIO/Redis/Toxiproxy 门禁由 `platform-storage -Pit` 提供，`Required CI` 另执行不联网的运行器/fixture 门禁。

## 常见问题

- `401 Unauthorized`：检查 `Authorization` 与登录账号是否正确。
- `400 缺少租户标识`：检查是否携带 `X-Tenant-ID`。
- `429 Too Many Requests`：触发全局限流，建议降低 `VUS` 或 arrival `rate`。
- 上传失败：确认 `chunk` 请求为 `PUT multipart/form-data`，路径包含 `clientId` 和 `chunkNumber`。
- 直传 PUT/GET 失败：确认 MinIO CORS 暴露 `ETag`，并确认压测脚本未向预签名 URL 添加平台鉴权头。
- cleanup 阈值失败：按 `RUN_ID` 搜索残留文件；不要关闭 `CLEANUP` 后把运行视为通过。
