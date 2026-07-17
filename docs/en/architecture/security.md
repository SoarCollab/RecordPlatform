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

### Distributed Rate Limiter

Redis Lua script-based sliding window:

```
ALLOWED → Request permitted
RATE_LIMITED → Window exceeded
BLOCKED → In block list
```

**Generic utility fallback**: The reusable distributed limiter allows requests if Redis is unavailable. This fallback does not apply to the public proof annotation bucket, which fails closed.

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

Automated security observation is configured as a GitHub Actions workflow:

- **Workflow**: `.github/workflows/security-poc.yml`
- **Scope**: SAST static analysis, SCA dependency scanning, SBOM generation
- **Current status**: Observation mode (informational, not blocking PRs)
- **Tooling**: Scripts and templates in `tools/security/`
- **Slither** (`.github/workflows/contract-security.yml`) — Solidity smart contract static analysis, triggered on `.sol` file changes or workflow updates, SARIF results uploaded to GitHub Security panel

> This pipeline is planned to be promoted to release-blocking in a future wave.

## Security Checklist

- [ ] `JWT_KEY` is ≥32 characters with high entropy
- [ ] SSL enabled in production
- [ ] CORS whitelist configured
- [ ] Rate limiting enabled
- [ ] Input validation on all endpoints
- [ ] SQL injection prevention (parameterized queries)
- [ ] XSS prevention (output encoding)
- [ ] Sensitive data not logged
