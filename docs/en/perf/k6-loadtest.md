# k6 Load Testing and Direct-Path Evidence

The repository provides repeatable, gated, and archivable k6 scenarios for query, backend-proxied chunk upload, mixed traffic, and the object-storage direct path.

## Scope and Gate Boundary

| Scenario | Covered flow |
| --- | --- |
| `file-query` | Basic, keyword, combined-filter file queries and file statistics |
| `chunk-upload` | Create legacy upload session, upload chunks, complete, query progress, cleanup |
| `core-mixed` | 70% query and 30% chunk upload by default |
| `direct-path` | Direct create → raw presigned PUT → complete → manifest metadata → raw presigned GET → size/hash verification → cleanup |
| `all` | Profile-dependent scenario composition |

`smoke/all` runs `file-query + core-mixed`. `load/all` runs `file-query + chunk-upload + direct-path`.

The external-environment workflow is manually triggered and is not a required pull-request check. Pull requests use the real MinIO/Redis/Toxiproxy integration gate from `platform-storage -Pit`; do not describe a manual k6 run as a PR gate.

`Required CI` additionally initializes all seven k6 entrypoints and their supported scenario selections under the pinned image with networking disabled, then executes local fixture assertions. This is a runtime/fixture gate, not a deployed-service smoke test.

## Prerequisites

- Backend reachable at `BASE_URL` (default `http://localhost:8000/record-platform/api/v1`).
- `TENANT_ID`, `USERNAME`, and `PASSWORD` provided explicitly; login also requires `X-Tenant-ID`.
- A verified k6 **0.57.0** release binary, or the explicit Docker engine. This is the tested compatibility baseline, not a claim that it is the latest or long-term-supported release. Newer binaries require independent compatibility validation.

`--engine auto` only selects a local k6 binary. Docker execution requires `--engine docker` and a digest-pinned `K6_DOCKER_IMAGE`. The workflow pins:

```text
grafana/k6@sha256:70af91f86cd8e142e0544a4edaf79835a80033f71974b92edd5ac36fd4442a7b
```

The old 0.49.0 default parser rejects object spread; its `base` mode also fails on the imported module graph. [k6 0.57.0](https://github.com/grafana/k6/releases/tag/v0.57.0) supplies modern syntax without an experimental compatibility flag. `tools/k6/runtime.env` is the canonical pin. For a local binary, verify the archive against the release checksum file before adding its directory to `PATH`.

```bash
# No target access, login, uploads, or cleanup:
bash tools/k6/check-runtime.sh docker
# Equivalent runtime check with the matching verified release binary:
K6_BINARY=/path/to/k6-v0.57.0/k6 bash tools/k6/check-runtime.sh local
```

### Private HTTPS and User-Run Acceptance

Trust the issuing CA or explicitly approved self-signed **public** certificate in the local OS trust store for native k6. Docker does not inherit macOS Keychain trust: set `K6_CA_CERT_FILE` to a readable local PEM trust bundle; both wrappers mount it read-only and set the container's `SSL_CERT_FILE`. The certificate must cover both API and presigned object-storage hosts. Never supply a private key or disable TLS verification. Keep credentials, certificates and result artifacts outside Git.

After deploying the intended `main` revision, privately export `BASE_URL`, `TENANT_ID`, `USERNAME`, and `PASSWORD` for a disposable account, then run:

```bash
source tools/k6/runtime.env
export K6_DOCKER_IMAGE="$K6_TESTED_IMAGE"
export K6_CA_CERT_FILE=/private/path/public-trust-bundle.pem
export VUS=1 DURATION=15s DIRECT_TOTAL_CHUNKS=2 DIRECT_CHUNK_SIZE=65536 CLEANUP=true
bash tools/k6/run-local.sh --profile smoke --scenario direct-path --engine docker \
  --run-id "acceptance-$(date +%Y%m%d%H%M%S)"
```

Require exit zero, non-zero completed-file/flow/cleanup samples, unchanged size/SHA-256 checks, and zero flow/cleanup failures. Verify the chain receipt separately if chain-level acceptance is required: this suite verifies file/manifest/object lifecycle, not an independent chain RPC receipt. Cleanup means the application's logical deletion; physical retention/sweep remains governed by server policy. Initialization alone does not establish deployed-service acceptance.

## Run Profiles

```bash
# Daily query and mixed smoke regression
bash tools/k6/run-local.sh --profile smoke --scenario all --engine auto

# Query, legacy upload, and direct-path load profile
bash tools/k6/run-local.sh --profile load --scenario all --engine auto

# Focused direct-path evidence
bash tools/k6/run-local.sh --profile smoke --scenario direct-path --engine auto
```

Supported values are:

- `K6_PROFILE=smoke|load`
- `K6_SCENARIO=all|file-query|chunk-upload|core-mixed|direct-path`
- `K6_ENGINE=auto|local|docker`

`core-mixed` is a smoke-only selection; load supports `all|file-query|chunk-upload|direct-path`.

The manual `.github/workflows/perf-smoke.yml` workflow defaults to `direct-path/smoke` and exposes profile, scenario, concurrency, duration, environment fingerprint, baseline path, resource snapshot path, and lifecycle snapshot path.

## Direct-Path Contract

Each direct iteration performs the complete lifecycle:

1. Create a direct upload session and validate the canonical part plan.
2. PUT deterministic bytes to every presigned staging URL.
3. Complete the session with ETag and `sha256:<lowercase-hex>` evidence.
4. Fetch manifest-backed download metadata.
5. GET each presigned object and verify response size, part hash, total size, and complete-file hash.
6. Delete the created file and verify cleanup evidence.

Raw presigned PUT/GET requests must not receive platform `Authorization`, `X-Tenant-ID`, or JSON headers. ETag is only an object-version condition; SHA-256 is the content identity. The direct suite disables k6 `url`/`name` system tags and forces `--log-output none`, preventing signed query parameters from entering metrics, logs, failure samples, or artifacts.

Both upload paths use `.txt` / `text/plain` and genuine printable ASCII/newline `ArrayBuffer` payloads, not renamed random binary data. The shared fixture preserves exact configured part sizes and derives deterministic content from run, VU, iteration, and part identity to avoid accidental cross-run deduplication. Production extension/MIME validation is unchanged. Give every run a new `RUN_ID`; repeating the same ID intentionally repeats fixture content.

## Thresholds

Global thresholds:

- `http_req_failed < 1%`
- `checks > 99%`

Query thresholds:

- `files_basic p95 < 800 ms`
- `files_keyword p95 < 800 ms`
- `files_combo p95 < 1,000 ms`
- `files_stats p95 < 800 ms`

Legacy upload thresholds:

- `upload_start p95 < 1,200 ms`
- `upload_chunk p95 < 1,500 ms`
- `upload_complete p95 < 1,500 ms`
- `upload_e2e_ms p95 < 6,000 ms`

Direct-path thresholds:

- `direct_flow_failure_rate == 0`
- `direct_cleanup_failure_rate == 0`
- upload, download, and end-to-end `p99 < DIRECT_P99_BUDGET_MS`

`DIRECT_P99_BUDGET_MS` defaults to 60,000 ms and is a test-level total budget, not a production SLA.

## Observation and Artifacts

Optional `DIRECT_RESOURCE_SNAPSHOT_PATH` and `DIRECT_LIFECYCLE_SNAPSHOT_PATH` are probed at run start and after cleanup. If a source is absent or cannot be read, the report records `unavailable` with a reason; it never invents zero heap, GC, thread, direct-buffer, staging, receipt, degraded, or repair values.

Every `RESULT_DIR` contains:

- `summary.txt` and `summary.json`
- `metrics.json`
- `query-baseline.json`
- `direct-path-baseline.json`
- `direct-path-report.md`
- `run-meta.json`

The direct baseline requires at least one flow sample, one cleanup sample, and one completed file. Setup-only or zero-iteration output cannot become valid evidence.

## Baseline Comparison

Only compare runs whose `environment.fingerprint` values and workload/execution contracts are identical:

```bash
node tools/k6/scripts/compare-direct-baseline.mjs \
  --baseline tools/k6/results/<BASE>/direct-path-baseline.json \
  --candidate tools/k6/results/<CANDIDATE>/direct-path-baseline.json \
  --output tools/k6/results/<CANDIDATE>/direct-path-comparison.md
```

The default comparison fails when p95/p99 regresses by more than 20%, upload/download throughput drops by more than 20%, or flow/cleanup changes from zero to non-zero. A different fingerprint, profile, scenario, engine, engine artifact, chunk plan, executor, concurrency, duration, or VU contract returns `NOT_COMPARABLE`, not a false regression result.

Query smoke/load results can be rendered into one Markdown evidence snippet:

```bash
node tools/k6/scripts/render-query-baseline.mjs \
  --smoke-dir tools/k6/results/<SMOKE_RUN_ID> \
  --load-dir tools/k6/results/<LOAD_RUN_ID> \
  --output tools/k6/results/query-baseline-snippet.md
```

## Troubleshooting

- `401`: validate credentials and token. A raw presigned 401/403 usually means the URL expired; obtain fresh metadata.
- Missing tenant identifier: include `X-Tenant-ID` for platform APIs, including login, but never for the raw signed URL.
- Direct PUT/GET failure: verify MinIO CORS exposes ETag and no platform header was added to the signed request.
- Cleanup threshold failure: retain the run artifacts and inspect the `RUN_ID`; a run with `CLEANUP=false` is not passing lifecycle evidence.
- `NOT_COMPARABLE`: align commit, configuration, target, engine, OS/architecture, CPU/memory, and k6 image fingerprint before drawing a performance conclusion.

The implementation-level variable list and script layout are maintained in [`tools/k6/README.md`](https://github.com/SoarCollab/RecordPlatform/blob/main/tools/k6/README.md).
