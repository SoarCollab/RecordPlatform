# Security

Comprehensive security mechanisms in RecordPlatform.

## Authentication

### JWT Token

- **Algorithm**: HMAC512 (upgraded from HMAC256)
- **Issuer/Audience**: Verified on every request
- **Key Validation**: Startup checks for key strength (≥32 chars, ≥128 bits entropy)
- **Blacklist**: Logged-out tokens stored in Redis

### Login Security

| Protection | Threshold | Duration |
|------------|-----------|----------|
| IP Rate Limit | 10 failures | 5 minutes |
| Account Lockout | 5 failures | 15 minutes |
| Captcha Required | 3 failures | Until success |

### SSE Token

EventSource doesn't support custom headers, so SSE uses URL token:

```
1. POST /api/v1/auth/tokens/sse → Get short-lived token (30s, single-use)
2. GET /api/v1/sse/connect?token=<token>&x-tenant-id=<tenantHint> → Establish SSE connection
```

The `X-Tenant-ID` header, `x-tenant-id` query value, and legacy `tenantId` query value are untrusted namespace hints only. `TenantContext`, request audit attributes, and MDC identity are established only after Redis atomically consumes the token and its tenant matches the hint. Invalid, expired, replayed, damaged, mismatched, or Redis-failed handshakes create no emitter. Anonymous failures are audited under system tenant `0`, and raw one-time tokens are excluded from text logs and persisted request parameters.

## Anonymous Public-Share Tenant Boundary

Only these four exact routes are anonymous:

- `GET /api/v1/shares/{shareCode}/info`
- `GET /api/v1/shares/{shareCode}/files`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`

They require neither JWT nor a tenant header. Caller-controlled `X-Tenant-ID` values are ignored rather than becoming an authoritative `TenantContext`. The service uses the matching `shareCode` metadata to resolve the owner tenant; only that share-metadata lookup crosses tenant isolation. File metadata, key envelopes, access-count changes, and share-access auditing then execute inside the owner tenant. Checks for public visibility, active/cancelled/expired or unknown state, supported share type, and file membership remain fail closed. The current model has no share-password field; password protection is outside this change.

Anonymous system operation audit is always attributed to system tenant `0`. A `share_access_log` row is attributed to the resolved owner tenant, never to a caller header. System audit, share audit, and public-share rate limiting consume the same canonical trusted-client IP. Share creation/update/cancellation, saving shared files, and the authenticated share download/decrypt routes remain protected by Bearer authentication.

## Authorization (RBAC)

### Role Definitions

| Role | Description | Default Permissions |
|------|-------------|---------------------|
| `user` | Regular user | File CRUD, share, tickets, messages |
| `admin` | Administrator | All permissions + audit, force delete |
| `monitor` | Monitor | Read-only + audit log viewing |

### Permission Format

```
module:action
```

Examples:
- `file:read` - Read files
- `file:admin` - Admin file operations (cross-user, force delete)
- `ticket:write` - Ticket operations
- `system:audit` - View audit logs

### Usage

```java
// Permission check
@PreAuthorize("hasPerm('file:admin')")

// Multiple permissions (any)
@PreAuthorize("hasAnyPerm('file:read', 'file:admin')")

// Role check
@PreAuthorize("isAdmin()")

// Resource ownership
@PreAuthorize("isOwner(#file.uid) or hasPerm('file:admin')")
```

### Resource Ownership Annotation

```java
@RequireOwnership(
    resourceIdParam = "id",
    ownerIdField = "uid",
    resourceClass = File.class,
    adminBypass = true
)
public Result<File> getFile(@PathVariable Long id) { ... }
```

## Rate Limiting

### Tiered Rate Limits

```java
@RateLimit(
    limit = 10,           // Regular users: 10/min
    period = 60,          // Time window (seconds)
    adminLimit = 100,     // Admins: 100/min
    type = LimitType.USER
)
```

### Limit Types

| Type | Key | Description |
|------|-----|-------------|
| `USER` | User ID | Per-user limit (default) |
| `IP` | IP Address | Per-IP limit |
| `API` | Endpoint | Global limit |

### Public Proof Shared Bucket

The public proof status and historical-key endpoints explicitly opt into a tenant-independent trusted-client-IP mode. They share `rate:limit:public:proof-verification:v2:ip:<canonical-ip>` at a fixed 120 requests per 60 seconds, even when a request carries a valid user/admin/monitor JWT. The key contains no tenant, user, endpoint method, or raw header; all other `@RateLimit` callers retain their legacy tenant, role, and forwarding-header behavior. By default the identity is the canonical direct socket peer and all forwarding headers are ignored. A numeric trusted-proxy allowlist is optional, empty by default, bounded to 4096 characters/64 ranges at startup, and parses one 1024-character/16-hop XFF chain right-to-left only after the immediate peer is trusted. Redis results other than `1`, including `null`, `0`, or dependency exceptions, never execute the controller.

### Public Share Shared Bucket

The public chunk and decrypt-info endpoints share `rate:limit:public:share-access:v2:ip:<canonical-ip>` at a fixed 30 requests per 60 seconds. The key contains no tenant, JWT role, endpoint method, or caller-supplied header value, so changing `X-Tenant-ID` or alternating between the two endpoints cannot split the bucket. The trusted-proxy boundary is the same one described above: forwarding headers are ignored unless the immediate peer matches the configured numeric allowlist. The first 30 combined requests may enter the controller; the current 31st request is denied through the existing response wrapper as HTTP 200 with business code `70005`. A Redis result other than `1`, including an exception, fails closed before the controller executes.

### Distributed Rate Limiter

Redis Lua script-based sliding window:

```
ALLOWED → Request permitted
RATE_LIMITED → Window exceeded
BLOCKED → In block list
```

**Generic utility fallback**: The reusable distributed limiter allows requests if Redis is unavailable. This fallback does not apply to the public proof or public-share annotation buckets, which fail closed.

## ID Obfuscation

External APIs use encrypted IDs, internal uses Snowflake IDs.

### Implementation

- **Algorithm**: AES-256-CTR + HMAC-SHA256 (SIV-style)
- **Format**: Prefix + Base62 (~40 chars)
  - `E` prefix: Entity IDs (files, records)
  - `U` prefix: User IDs
- **Key Derivation**: HKDF from `JWT_KEY`

### Data Structure

```
Plaintext (16B): [version:1][type:1][id:8][padding:6]
Ciphertext (42B): [SIV:16][AES-CTR(plaintext):16][HMAC:10]
Output: prefix + Base62(ciphertext) ≈ 40 chars
```

## File Encryption

### Supported Algorithms

| Algorithm | Best For | Performance |
|-----------|----------|-------------|
| AES-256-GCM | AES-NI hardware | 1000+ MB/s |
| ChaCha20-Poly1305 | Containers, ARM | Consistent |

### Configuration

```yaml
file:
  encryption:
    algorithm: chacha20  # aes-gcm, chacha20, auto
    benchmark-on-startup: false
```

### File Format (v2.0+)

```
[Header: 4B] [IV: 12B] [Encrypted Data] [Auth Tag] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
```

Header: Magic `RP` (0x52 0x50) + Version (0x01) + Algorithm (0x01=AES, 0x02=ChaCha20)

### Data-key wrapping providers

The serialized file data key is removed from persisted file metadata and stored only as a wrapped `file_key_envelope`. The provider-neutral boundary supports `local` contract v1 and `vault-transit` contract v1:

- new writes use the explicitly configured active provider;
- reads use the persisted `(kmsProvider, providerContractVersion)` and never fall back;
- `rp-file-envelope-aad-v1` permanently preserves the legacy local byte order;
- local metadata validates key ID, provider key version, wrapping algorithm, and context schema; previous key IDs must be explicitly allowlisted while referenced;
- external `rp-file-envelope-context-v2` binds tenant, file, hash, recipient, and suite, while Vault receives only the Base64 SHA-256 digest of those canonical bytes;
- same-named Vault key rotation uses Transit `rewrap`; cross-provider rotation is a controlled unwrap/wrap operation;
- audit and metrics contain stable failure categories and a key-ID fingerprint, never a raw key ID, wrapped blob, plaintext data key, token, or Vault error body.

Vault Community integration tests prove the Transit API contract. They do not prove HSM custody. Production HSM-backed deployments require Vault Enterprise with PKCS#11 seal wrap or Managed Keys, plus the relevant licenses, hardware, and high-availability design.

## HTTPS Enforcement

Production auto-redirects HTTP to HTTPS:

```yaml
server:
  port: 443
  ssl:
    enabled: true
    key-store: classpath:keystore.p12

security:
  require-ssl: true
  http-redirect-port: 80
```

## CI Security Pipeline

Security automation has two distinct enforcement boundaries:

- **Workflow**: `.github/workflows/security-poc.yml`
- **Scope**: SAST static analysis, SCA dependency scanning, SBOM generation
- **Current status**: The full `security-poc` aggregation remains observation-only and is not a required check
- **Tooling**: Scripts and templates in `tools/security/`
- **Slither** (`.github/workflows/contract-security.yml`) — Solidity smart contract static analysis, triggered on `.sol` file changes or workflow updates, SARIF results uploaded to GitHub Security panel
- **Test Suite blocking subset** (`.github/workflows/test.yml`) — frontend `pnpm audit --audit-level high` and the explicit Trivy fixed High/Critical scan fail their job when findings breach the configured threshold

These facts do not mean every security observation is blocking or that repository advisories are zero. P6 still owns a unified policy for the remaining scanners, advisory backlog, exceptions, and release enforcement.

## Security Checklist

- [ ] `JWT_KEY` is ≥32 characters with high entropy
- [ ] SSL enabled in production
- [ ] CORS whitelist configured
- [ ] Rate limiting enabled
- [ ] Input validation on all endpoints
- [ ] SQL injection prevention (parameterized queries)
- [ ] XSS prevention (output encoding)
- [ ] Sensitive data not logged
