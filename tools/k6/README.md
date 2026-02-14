# K6 压测框架（本地 + CI）

本目录提供可复用、可门禁、可归档的 K6 压测框架，覆盖：
- 读路径：`/files`（`basic/keyword/combo`）与 `/files/stats`
- 写路径：`/files/upload/start`、`/files/upload/chunk`、`/files/upload/complete`、`/files/upload/progress`
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
├── chunk-upload.js
├── file-query.js
├── run-ci.sh
└── run-local.sh
```

## 前置条件

- 后端服务可访问（默认：`http://localhost:8000/record-platform/api/v1`）
- 已安装 K6（macOS）：

```bash
brew install k6
```

> 注意：`X-Tenant-ID` 对 `/api/v1/auth/login` 也必填。

## 环境变量

基础变量：
- `BASE_URL`（默认：`http://localhost:8000/record-platform/api/v1`）
- `TENANT_ID`（默认：`1`）
- `USERNAME`（默认：`loadtest`）
- `PASSWORD`（默认：`loadtest123`）

框架变量：
- `K6_PROFILE=smoke|load`
- `K6_SCENARIO=all|file-query|chunk-upload|core-mixed`
- `RUN_ID`（默认当前时间戳）
- `RESULT_DIR`（默认 `tools/k6/results/<RUN_ID>`）

上传变量：
- `TOTAL_CHUNKS`（默认 `5`）
- `CHUNK_SIZE`（默认 `1024` 字节）

可选变量：
- `CLEANUP=true|false`（默认 `true`）
- `CI_INCLUDE_CHUNK=true|false`（默认 `false`）

## 本地运行

### 1) smoke 档位（推荐日常回归）

默认 `K6_SCENARIO=all` 时只跑 `file-query + core-mixed`。

```bash
bash tools/k6/run-local.sh --profile smoke --scenario all
```

### 2) load 档位（查询/上传控速）

默认 `K6_SCENARIO=all` 时跑 `file-query + chunk-upload`。

```bash
bash tools/k6/run-local.sh --profile load --scenario all
```

### 3) 指定单场景

```bash
bash tools/k6/run-local.sh --profile smoke --scenario file-query
bash tools/k6/run-local.sh --profile smoke --scenario core-mixed
bash tools/k6/run-local.sh --profile smoke --scenario chunk-upload
bash tools/k6/run-local.sh --profile load --scenario chunk-upload
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

## 报告产物

每次运行会在 `RESULT_DIR` 输出：
- `summary.txt`：可读摘要（含 endpoint 的 p50/p90/p95、错误率、请求量、阈值结果、失败样本）
- `summary.json`：完整 k6 summary
- `metrics.json`：精简指标快照

## CI 说明

- CI 套件入口：`tools/k6/suites/ci-smoke.js`
- 本地脚本入口：`tools/k6/run-ci.sh`
- 工作流：`.github/workflows/perf-smoke.yml`

`run-ci.sh` 必需环境变量（仅支持统一变量名）：
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

GitHub Actions Secrets（与统一变量同名）：
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

手工触发时可选择是否包含 `chunk-upload` 场景。

## 常见问题

- `401 Unauthorized`：检查 `Authorization` 与登录账号是否正确。
- `400 缺少租户标识`：检查是否携带 `X-Tenant-ID`。
- `429 Too Many Requests`：触发全局限流，建议降低 `VUS` 或 arrival `rate`。
- 上传失败：确认 `chunk` 请求为 `multipart/form-data`，并包含 `file/clientId/chunkNumber`。
