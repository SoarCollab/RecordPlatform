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

## 3. 快速运行

### 3.1 smoke 回归（推荐）

默认 `K6_SCENARIO=all` 时，执行 `file-query + core-mixed`。

```bash
bash tools/k6/run-local.sh --profile smoke --scenario all
```

### 3.2 load 压测

默认 `K6_SCENARIO=all` 时，执行 `file-query + chunk-upload`。

```bash
bash tools/k6/run-local.sh --profile load --scenario all
```

### 3.3 指定单场景

```bash
bash tools/k6/run-local.sh --profile smoke --scenario file-query
bash tools/k6/run-local.sh --profile smoke --scenario core-mixed
bash tools/k6/run-local.sh --profile smoke --scenario chunk-upload
bash tools/k6/run-local.sh --profile load --scenario chunk-upload
```

## 4. 接口契约（按后端代码）

### 4.1 登录
- `POST /api/v1/auth/login`
- `Content-Type: application/json`
- body：`{"username":"...","password":"..."}`
- 必须携带 `X-Tenant-ID`

### 4.2 查询链路
- `GET /api/v1/files/page?pageNum=1&pageSize=10`
- `GET /api/v1/files/list`
- `GET /api/v1/files/stats`

### 4.3 上传链路
- `POST /api/v1/files/upload/start`（`@RequestParam`）
  - `fileName/fileSize/contentType/chunkSize/totalChunks`（可选 `clientId`）
- `POST /api/v1/files/upload/chunk`（`multipart/form-data`）
  - `file/clientId/chunkNumber`
- `POST /api/v1/files/upload/complete`（`clientId`）
- `GET /api/v1/files/upload/progress?clientId=...`

### 4.4 清理链路
- `DELETE /api/v1/files/delete?identifiers=...`
- teardown 会按 `runId` 搜索并批量逻辑删除。

## 5. 固定门禁阈值

全局：
- `http_req_failed < 1%`
- `checks > 99%`

查询链路：
- `files_page p95 < 800ms`
- `files_list p95 < 800ms`
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
