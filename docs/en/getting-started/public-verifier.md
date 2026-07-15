# Public Verifier

`platform-verifier` is an independently deployable verification surface, isolated from the RecordPlatform business backend. It does not read the platform database, require a tenant or login token, or persist uploaded bytes in business storage. The SDK, CLI, and Web service share one signed proof ZIP v2 implementation.

## Decision boundary

The stable report has four outcomes:

| Outcome | Meaning |
| --- | --- |
| `VALID` | File hash, chunks, Merkle path, trusted Ed25519 signature, current `ACTIVE` status, and live chain root all pass |
| `INVALID` | A definitive tamper, mismatch, bad signature, revoked/superseded/invalid status, or live-root mismatch exists |
| `INDETERMINATE` | Local evidence is consistent, but a trusted key, current status, or chain dependency is disabled, unknown, or unavailable; this is not validity |
| `ERROR` | The ZIP/JSON is malformed, a resource limit is exceeded, I/O fails, or the verifier cannot complete safely |

`VALID` always requires trusted signature, current status, and live-chain evidence. Offline execution never trusts key metadata embedded in the proof archive by itself.

## Build

Run from the repository root:

```bash
mvn -f platform-verifier/pom.xml clean verify
```

Artifacts:

- `platform-verifier/sdk/target/platform-verifier-sdk-0.0.2-SNAPSHOT.jar`
- `platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar`
- `platform-verifier/web-verifier/target/platform-verifier-web.jar`
- `platform-verifier/sdk/target/verifier-fixtures/`: CI-generated original file, proof archive, trusted key, and expected report examples

The SDK enforces a 70% line-coverage gate. CLI and Web are adapters only; neither duplicates hashing, Merkle, signature, status, chain, or outcome rules.

## CLI

### Local key trust with offline status and chain checks

```bash
java -jar platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar verify \
  --file ./original.pdf \
  --proof ./record-proof-file-1.zip \
  --trusted-key ./trusted-key.json \
  --format json
```

The trust file is strict JSON. Unknown fields, duplicate fields, and trailing content are rejected:

```json
{
  "algorithm": "EdDSA",
  "keyId": "record-platform-proof-2026",
  "keyVersion": 1,
  "publicKeyFingerprint": "sha256:<64 lowercase hexadecimal characters>",
  "publicKeySpki": "<Base64-encoded X.509 SPKI>"
}
```

A local key establishes signature trust only. Without current-status and live-chain resolvers, the overall outcome remains `INDETERMINATE`.

### Explicit online verification

```bash
java -jar platform-verifier/cli-verifier/target/record-platform-verifier-exec.jar verify \
  --file ./original.pdf \
  --proof ./record-proof-file-1.zip \
  --online \
  --issuer-base-url https://record.example/record-platform \
  --chain-url-template 'https://chain.example/api/v1/roots/{chainType}/{chainId}/{groupId}/{contractAddress}/{batchNo}' \
  --allow-host record.example \
  --allow-host chain.example \
  --format text
```

Online mode never uses a URL from the ZIP as a network target. The caller must explicitly configure the issuer origin, chain-gateway template, and exact host allowlist. HTTPS is required by default; redirects and private/loopback targets are rejected, and connection, request, and response-size limits apply.

Use `--allow-http --allow-private-addresses` only for local tests, never production.

### Online resolver contracts

`--issuer-base-url` points at the RecordPlatform deployment context and requires neither JWT nor tenant headers. Under that base, the verifier only requests the exact key/proof identities declared by the signed evidence:

- `GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}`;
- `GET /api/v1/public/proofs/{proofId}/status`.

Both responses must use `application/json` or `application/*+json` and the strict platform envelope with `code = 200`. Example signing-key response:

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "keyId": "record-platform-proof-2026",
    "keyVersion": 1,
    "algorithm": "EdDSA",
    "publicKeySpki": "<Base64-encoded X.509 SPKI>",
    "publicKeyFingerprint": "sha256:<64 lowercase hexadecimal characters>"
  }
}
```

Example status response:

```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "proofId": "rp-proof-<64 lowercase hexadecimal characters>",
    "status": "ACTIVE",
    "statusVersion": "1",
    "issuedStatus": "ACTIVE",
    "keyId": "record-platform-proof-2026",
    "keyVersion": 1,
    "reason": null,
    "issuedAt": "2026-07-15T01:02:03Z",
    "updatedAt": "2026-07-15T01:02:03Z"
  }
}
```

The backend may encode `statusVersion` as a JSON string or integer, but it must represent a positive integer. Timestamps may be RFC 3339 strings or epoch-millisecond integers. `status` is limited to `ACTIVE`, `REVOKED`, `SUPERSEDED`, or `INVALID`, and proof/key identities must exactly match the signed manifest. Status is never cached; keys are cached for at most the configured five minutes.

`--chain-url-template` is an operator-owned fixed gateway template. It may contain `{chainType}`, `{chainId}`, `{groupId}`, `{contractAddress}`, and `{batchNo}`. The gateway returns a direct JSON object, not the platform envelope:

```json
{
  "schemaVersion": "record-platform-chain-root-resolution.v1",
  "chainType": "LOCAL_FISCO",
  "chainId": "chain0",
  "groupId": "group0",
  "contractAddress": "0x1111111111111111111111111111111111111111",
  "batchNo": "MB-900",
  "merkleRoot": "<64-hex batch root>",
  "transactionHash": "0x<64-hex transaction hash>",
  "blockNumber": 100
}
```

The response schema, chain/group/contract/batch identity, Merkle root, equivalent transaction hash, and non-negative block number must match the signed evidence. For query-sourced receipts with no transaction hash, the response must also omit it or use `null`. A 404 is unknown; 429/5xx, timeout, or connection failure is unavailable. All produce `INDETERMINATE`, never a fallback `VALID`.

### Exit codes

| Exit code | Outcome |
| --- | --- |
| `0` | `VALID` |
| `2` | `INVALID` |
| `3` | `INDETERMINATE` |
| `4` | `ERROR` |
| `64` | Command usage error |

Automation must inspect both the exit code and `record-platform-verification-report.v1`; it must not treat `INDETERMINATE` as success.

## Web service

### Direct execution

```bash
java -jar platform-verifier/web-verifier/target/platform-verifier-web.jar
```

The default listener is port `8093`:

- `GET /`: self-contained verifier UI;
- `POST /api/v1/verify`: `multipart/form-data` with required `original` and `proof`, plus optional `trustedKey`;
- `GET /actuator/health`: health surface without dependency details.

### Container

```bash
docker build -f platform-verifier/web-verifier/Dockerfile \
  -t recordplatform-verifier-web .
docker run --rm -p 8093:8093 recordplatform-verifier-web
```

The image runs as a non-root user. In production, keep the root filesystem read-only and mount `/tmp/record-platform-verifier` as a capacity-limited temporary volume.

### Key configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `VERIFIER_PORT` | `8093` | HTTP port |
| `VERIFIER_MAX_ORIGINAL_FILE_BYTES` | `1073741824` | SDK streaming limit for the original file |
| `VERIFIER_MULTIPART_MAX_FILE_SIZE` | `1GB` | Servlet per-part limit; keep it aligned with the business file limit |
| `VERIFIER_MULTIPART_MAX_REQUEST_SIZE` | `1100MB` | Whole multipart request limit; raise it explicitly when raising the file limit |
| `VERIFIER_MAX_CONCURRENT` | `4` | Process-wide concurrent verification permits |
| `VERIFIER_ACQUIRE_TIMEOUT` | `100ms` | Maximum wait for a permit |
| `VERIFIER_RATE_LIMIT_REQUESTS` | `20` | Direct-peer requests per window |
| `VERIFIER_RATE_LIMIT_WINDOW` | `1m` | Rate-limit window |
| `VERIFIER_RATE_LIMIT_MAX_CLIENTS` | `10000` | Maximum direct-peer counters retained in memory |
| `VERIFIER_ONLINE_ENABLED` | `false` | Enables controlled online resolution |
| `VERIFIER_ISSUER_BASE_URI` | empty | Trusted issuer public API base URI |
| `VERIFIER_CHAIN_URL_TEMPLATE` | empty | Trusted chain-root gateway path template |
| `VERIFIER_ALLOWED_HOSTS` | empty | Comma-separated exact hostname allowlist |
| `VERIFIER_ALLOW_HTTP` | `false` | Allows plaintext HTTP for tests only |
| `VERIFIER_ALLOW_PRIVATE_ADDRESSES` | `false` | Allows private/loopback targets for tests only |
| `VERIFIER_CONNECT_TIMEOUT` | `3s` | Online resolver connection timeout |
| `VERIFIER_REQUEST_TIMEOUT` | `5s` | Online resolver whole-request timeout |

When online mode is enabled, a missing issuer URI, chain template, or allowlist prevents startup instead of silently enabling partial online verification.

## Security and privacy

- ZIP data is never extracted. The parser accepts exactly eight root-level STORED entries and rejects compression, comments, extra fields, prepended/trailing bytes, gaps, overlaps, nesting, traversal, symlinks, and limits violations.
- JSON parsing rejects duplicates, unknown fields, trailing tokens, oversized documents, excessive depth, and oversized strings or numbers.
- Original files are hashed as streams and are not loaded into the Java heap as a whole. Web rate and concurrency admission run before lazy multipart parsing; admitted multipart data is disk-spooled and request-scoped temporary files are removed afterward.
- Web rate limiting uses the direct socket peer and does not trust caller-controlled `X-Forwarded-For`. A reverse proxy should apply its own outer rate limit.
- Logs and errors do not include original bytes, trust-file content, private keys (the verifier never accepts one), remote response bodies, or temporary paths.
- Signing keys may be cached for five minutes and chain roots briefly; current revocation status is never cached.

Servlet multipart spooling and the SDK's private copy may coexist during a verification. Size temporary storage for at least `maximum request size × concurrency × 2`, plus filesystem and log headroom. Use a dedicated capacity-limited volume with a host cleanup policy.

The resolver rejects private, loopback, and reserved addresses before each request, but the JDK HTTP client cannot atomically pin that pre-resolved address to its later connection. In production, complement exact host allowlists and HTTPS with container/host egress ACLs that block metadata endpoints, internal networks, and every other unexpected range; this covers residual DNS-rebinding risk.

## Rollback

The verifier adds no database migration or persisted-data conversion. Stop Web instances, remove CLI/SDK artifacts, and revert the corresponding commit. Existing signed v2 ZIPs and public key/status endpoints remain unchanged.
