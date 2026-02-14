# k6 Load Testing (Local + CI)

Goal: provide a repeatable, gated, and archivable performance baseline and regression workflow.

## 1. Start Backend (local profile)

This repository ships with a local load-test seed (enabled by default in the local profile):
- Tenant: `tenantId=1`
- User: `loadtest` / `loadtest123`

Set the JWT key (to avoid startup errors):

```bash
export JWT_KEY="ci-integration-jwt-key-32chars-xK9mN2pL5qR8vW3y"
```

Start the backend (example):

```bash
mvn -f platform-backend/pom.xml -pl backend-web -am spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Default Base URL:

```text
http://localhost:8000/record-platform/api/v1
```

## 2. Install k6 (macOS)

```bash
brew install k6
```

## 3. Quick Run

### 3.1 Smoke regression (recommended)

When `K6_SCENARIO=all` (default), runs `file-query + core-mixed`.

```bash
bash tools/k6/run-local.sh --profile smoke --scenario all
```

### 3.2 Load test

When `K6_SCENARIO=all` (default), runs `file-query + chunk-upload`.

```bash
bash tools/k6/run-local.sh --profile load --scenario all
```

### 3.3 Single scenario

```bash
bash tools/k6/run-local.sh --profile smoke --scenario file-query
bash tools/k6/run-local.sh --profile smoke --scenario core-mixed
bash tools/k6/run-local.sh --profile smoke --scenario chunk-upload
bash tools/k6/run-local.sh --profile load --scenario chunk-upload
```

## 4. API Contract (per backend code)

### 4.1 Login
- `POST /api/v1/auth/login`
- `Content-Type: application/json`
- Body: `{"username":"...","password":"..."}`
- Must include `X-Tenant-ID` header

### 4.2 Query chain
- `GET /api/v1/files?pageNum=1&pageSize=10` (basic)
- `GET /api/v1/files?pageNum=1&pageSize=10&keyword=...` (keyword)
- `GET /api/v1/files?pageNum=1&pageSize=10&keyword=...&status=1&startTime=...&endTime=...` (combo)
- `GET /api/v1/files/stats`

### 4.3 Upload chain
- `POST /api/v1/files/upload/start` (`@RequestParam`)
  - `fileName/fileSize/contentType/chunkSize/totalChunks` (optional `clientId`)
- `POST /api/v1/files/upload/chunk` (`multipart/form-data`)
  - `file/clientId/chunkNumber`
- `POST /api/v1/files/upload/complete` (`clientId`)
- `GET /api/v1/files/upload/progress?clientId=...`

### 4.4 Cleanup chain
- `DELETE /api/v1/files/delete?identifiers=...`
- Teardown searches by `runId` and batch soft-deletes.

## 5. Gate Thresholds

Global:
- `http_req_failed < 1%`
- `checks > 99%`

Query chain:
- `files_basic p95 < 800ms`
- `files_keyword p95 < 800ms`
- `files_combo p95 < 1000ms`
- `files_stats p95 < 800ms`

Upload chain:
- `upload_start p95 < 1200ms`
- `upload_chunk p95 < 1500ms`
- `upload_complete p95 < 1500ms`
- `upload_e2e_ms p95 < 6000ms`

## 6. Report Artifacts

Each run outputs to `RESULT_DIR`:
- `summary.txt` (includes per-endpoint p50/p90/p95, error rate, request count, threshold results, failed samples)
- `summary.json`
- `metrics.json`

Default directory: `tools/k6/results/<RUN_ID>/`

## 7. CI Smoke

Workflow: `.github/workflows/perf-smoke.yml`

`run-ci.sh` required environment variables (unified naming only):
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

GitHub Actions Secrets (same names as unified variables):
- `BASE_URL`
- `TENANT_ID`
- `USERNAME`
- `PASSWORD`

Supports:
- Manual trigger (`workflow_dispatch`)
- Scheduled (`schedule`)

Optional:
- When manually triggered, you can choose whether to include the `chunk-upload` scenario (`include_chunk_upload=true`).

## 8. Common Failure Troubleshooting

- `401`: Wrong username/password or invalid token.
- `400 missing tenant identifier`: Request missing `X-Tenant-ID` header.
- `429`: Global rate limit triggered; lower `VUS` or arrival `rate`.
- Upload failure: Verify that `chunk` uses `multipart/form-data` with all required fields.
