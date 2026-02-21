# k6 压测（本地 + CI）

目标：提供可重复、可门禁、可归档的性能基线与回归流程。

## 1. 启动后端（local profile）

本仓库已提供本地压测 seed（默认 local profile 启用）：
- 租户：`tenantId=1`
- 用户：`loadtest` / `loadtest123`

设置 JWT 密钥（避免启动报错）：

```bash
export JWT_KEY="ci-integration-jwt-key-32chars-xK9mN2pL5qR8vW3y"
```

启动后端（示例）：

```bash
mvn -f platform-backend/pom.xml -pl backend-web -am spring-boot:run \
  -Dspring-boot.run.profiles=local
```

默认 Base URL：

```text
http://localhost:8000/record-platform/api/v1
```

## 2. 安装 k6（macOS）

```bash
brew install k6
```

若本机未安装 `k6`，可使用 `--engine auto` 自动回退到 Docker（`grafana/k6:0.49.0`）。

## 3. 快速运行

### 3.1 smoke 回归（推荐）

默认 `K6_SCENARIO=all` 时，执行 `file-query + core-mixed`。

```bash
bash tools/k6/run-local.sh --profile smoke --scenario all --engine auto
```

`core-mixed` 场景将文件查询和分片上传流程按权重混合执行。默认 70% 迭代执行查询流程，30% 执行上传流程。可通过 `MIX_QUERY_WEIGHT` 环境变量控制查询/上传比例（0-100，默认 70）：

```bash
MIX_QUERY_WEIGHT=50 bash tools/k6/run-local.sh --profile smoke --scenario core-mixed --engine auto
```

### 3.2 load 压测

默认 `K6_SCENARIO=all` 时，执行 `file-query + chunk-upload`。

```bash
bash tools/k6/run-local.sh --profile load --scenario all --engine auto
```

### 3.3 指定单场景

```bash
bash tools/k6/run-local.sh --profile smoke --scenario file-query --engine auto
bash tools/k6/run-local.sh --profile smoke --scenario core-mixed --engine auto
bash tools/k6/run-local.sh --profile smoke --scenario chunk-upload --engine auto
bash tools/k6/run-local.sh --profile load --scenario chunk-upload --engine auto
```

## 4. 接口契约（按后端代码）

### 4.1 登录
- `POST /api/v1/auth/login`
- `Content-Type: application/json`
- body：`{"username":"...","password":"..."}`
- 必须携带 `X-Tenant-ID`

### 4.2 查询链路
- `GET /api/v1/files?pageNum=1&pageSize=10`（basic）
- `GET /api/v1/files?pageNum=1&pageSize=10&keyword=...&keywordMode=PREFIX`（keyword）
- `GET /api/v1/files?pageNum=1&pageSize=10&keyword=...&keywordMode=PREFIX&status=1&startTime=...&endTime=...`（combo）
- `GET /api/v1/files/stats`

### 4.3 上传链路
- `POST /api/v1/upload-sessions`（`@RequestParam`）
  - `fileName/fileSize/contentType/chunkSize/totalChunks`（可选 `clientId`）
- `PUT /api/v1/upload-sessions/{clientId}/chunks/{chunkNumber}`（`multipart/form-data`）
  - `file/clientId/chunkNumber`
- `POST /api/v1/upload-sessions/{clientId}/complete`（`clientId`）
- `GET /api/v1/upload-sessions/{clientId}/progress`

### 4.4 清理链路
- `DELETE /api/v1/files/delete?identifiers=...`
- teardown 会按 `runId` 搜索并批量逻辑删除。

## 5. 固定门禁阈值

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

## 6. 报告产物

每次运行输出到 `RESULT_DIR`：
- `summary.txt`（含 endpoint 的 p50/p90/p95、错误率、请求量、阈值结果、失败样本）
- `summary.json`
- `metrics.json`
- `query-baseline.json`（endpoint 指标 + 阈值结果，便于报告回填）
- `run-meta.json`（`runId/profile/scenario/engine/timestamp/baseUrlMask`）

默认目录：`tools/k6/results/<RUN_ID>/`

## 7. CI smoke

工作流：`.github/workflows/perf-smoke.yml`

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

支持：
- 手工触发 (`workflow_dispatch`)
- 每日定时 (`schedule`)

可选：
- 手工触发时可选择是否包含 `chunk-upload` 场景（`include_chunk_upload=true`）。

## 8. 常见失败排查

- `401`：账号/密码错误或 token 无效。
- `400 缺少租户标识`：请求未带 `X-Tenant-ID`。
- `429`：触发全局限流；降低 `VUS` 或 arrival `rate`。
- 上传失败：确认 `chunk` 为 `multipart/form-data` 且字段完整。
