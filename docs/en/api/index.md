# API Reference

RecordPlatform REST API index aligned with current controllers and security rules.

## Interactive Docs

- **Swagger UI**: http://localhost:8000/record-platform/swagger-ui.html
- **OpenAPI**: http://localhost:8000/record-platform/v3/api-docs

Swagger Basic auth (Knife4j): `admin` / `123456` by default (configurable).

## Base URL

```text
http://localhost:8000/record-platform
```

## Authentication and Public Rules

### 1) Standard JWT auth

Most endpoints require:

```http
Authorization: Bearer <token>
```

### 2) Explicit public endpoints (`permitAll`)

Based on `SecurityConfiguration`:

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/verification-codes`
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/password-resets/confirm`
- `PUT /api/v1/auth/password-resets`
- `GET /api/v1/shares/{shareCode}/info`
- `GET /api/v1/shares/{shareCode}/files`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`
- `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`
- `GET /api/v1/images/download/images/**`
- `GET /api/v1/public/proofs/{proofId}/status`
- `GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}`
- `GET /api/v1/sse/connect` (still requires short-lived token)

The anonymous public-share surface is limited to the four exact `GET` routes above. No other share route is implicitly public.

### 3) SSE dual-token flow

- `POST /api/v1/auth/tokens/sse`: requires standard JWT
- `GET /api/v1/sse/connect?token=...&x-tenant-id=...`: public route, but a short-lived one-time token is mandatory. The tenant query value is only a Redis namespace hint; the consumed token is the authority for tenant, user, and role.

## Endpoints by Module

### Auth (`/api/v1/auth`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/auth/verification-codes` | Request email verification code |
| POST | `/api/v1/auth/register` | Register user |
| POST | `/api/v1/auth/password-resets/confirm` | Confirm password reset |
| PUT | `/api/v1/auth/password-resets` | Execute password reset |
| POST | `/api/v1/auth/tokens/refresh` | Refresh access token |
| POST | `/api/v1/auth/tokens/sse` | Issue short-lived SSE token (JWT required) |

> Login/logout are handled by Spring Security (non-controller endpoints): `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`

### User (`/api/v1/users`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/users/info` | Get user profile |
| PUT | `/api/v1/users/info` | Update user profile |
| PUT | `/api/v1/users/email` | Change email |
| PUT | `/api/v1/users/password` | Change password |

### File Upload (`/api/v1/upload-sessions`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/upload-sessions` | Start chunked upload |
| POST | `/api/v1/upload-sessions/direct` | Start direct multipart upload and return presigned URLs |
| PUT | `/api/v1/upload-sessions/{clientId}/chunks/{chunkNumber}` | Upload chunk |
| POST | `/api/v1/upload-sessions/{clientId}/complete` | Complete upload |
| POST | `/api/v1/upload-sessions/{clientId}/direct/complete` | Complete direct multipart upload |
| POST | `/api/v1/upload-sessions/{clientId}/pause` | Pause upload |
| POST | `/api/v1/upload-sessions/{clientId}/resume` | Resume upload |
| DELETE | `/api/v1/upload-sessions/{clientId}` | Cancel upload |
| DELETE | `/api/v1/upload-sessions/{clientId}/direct` | Abort direct multipart upload |
| GET | `/api/v1/upload-sessions/{clientId}` | Check upload status |
| GET | `/api/v1/upload-sessions/{clientId}/progress` | Query upload progress |

### Files and Sharing (`/api/v1/files`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/files/{id}` | File detail by ID |
| GET | `/api/v1/files/{id}/proof-bundle` | Export verifier-ready proof bundle by file ID |
| GET | `/api/v1/files/attestation-leaves/{leafId}/proof-bundle` | Export verifier-ready proof bundle by attestation leaf ID |
| GET | `/api/v1/files/{id}/proof-bundle.zip` | Export a deterministic Ed25519-signed proof ZIP by file ID |
| GET | `/api/v1/files/attestation-leaves/{leafId}/proof-bundle.zip` | Export a deterministic signed proof ZIP by attestation leaf ID |
| POST | `/api/v1/files/attestation-leaves/{leafId}/proof-status/revoke` | Idempotently revoke a signed proof managed by the current user |
| GET | `/api/v1/files/hash/{fileHash}` | File detail by hash |
| GET | `/api/v1/files` | User file page (supports `keyword`, `keywordMode=FUZZY/PREFIX/EXACT_HASH/AUTO`, `status`, `startTime`, `endTime`) |
| GET | `/api/v1/files/stats` | User file stats |
| GET | `/api/v1/files/hash/{fileHash}/download-metadata` | Fetch manifest-backed presigned chunk download metadata |
| GET | `/api/v1/files/hash/{fileHash}/addresses` | Fetch URL-only download addresses |
| GET | `/api/v1/transactions/{transactionHash}` | Query blockchain transaction |
| GET | `/api/v1/files/hash/{fileHash}/chunks` | Download file via backend byte payload |
| GET | `/api/v1/files/hash/{fileHash}/decrypt-info` | Decrypt info (authenticated) |
| GET | `/api/v1/shares/{shareCode}/files` | Public share file list (public) |
| GET | `/api/v1/files/shares` | My share list |
| DELETE | `/api/v1/files` | Batch delete (hash/id) |
| DELETE | `/api/v1/files/{id}` | Delete by file ID (admin) |
| POST | `/api/v1/shares` | Create share |
| PATCH | `/api/v1/shares/{shareCode}` | Update share |
| DELETE | `/api/v1/files/share/{shareCode}` | Cancel share |
| POST | `/api/v1/shares/{shareCode}/files/save` | Save shared files to my space |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/chunks` | Shared download (authenticated) |
| GET | `/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info` | Shared decrypt info (authenticated) |
| GET | `/api/v1/files/share/{shareCode}/access-logs` | Share access logs (admin) |
| GET | `/api/v1/files/share/{shareCode}/stats` | Share access stats (admin) |
| GET | `/api/v1/files/{id}/provenance` | File provenance graph (admin) |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/chunks` | Public shared download (public) |
| GET | `/api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info` | Public decrypt info (public) |
| POST | `/api/v1/files/download-batches/report` | Report batch download quality metrics |
| GET | `/api/v1/files/{id}/versions` | List version chain for a file |
| POST | `/api/v1/files/{id}/versions` | Mark file as parent for a new version upload |

Owned-file `download-metadata` requires an active `cn.flying.chunk-manifest.v1`. Success includes `canonicalManifestJson`, `manifestStatus=ACTIVE`, `manifestClassification=ALREADY_MANIFEST`, `manifestErrorCode=null`, and `legacyDownloadAllowed=false`. A missing manifest returns `FILE_RECORD_ERROR`; its standard `ErrorPayload` carries the four machine-readable governance fields under `data.detail`. The unclassified default is `REUPLOAD_REQUIRED / UNCLASSIFIED / MISSING_MANIFEST_UNCLASSIFIED / false`. Clients must not parse `message` or infer a legacy fallback. See [Chunk Manifest and Legacy Governance](/en/architecture/chunk-manifest).

The exact anonymous public-share contract consists of `GET /api/v1/shares/{shareCode}/info`, `GET /api/v1/shares/{shareCode}/files`, `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/chunks`, and `GET /api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info`. These routes require neither Bearer authentication nor a tenant header. Any supplied `X-Tenant-ID`, including `0`, another tenant, or a malformed value, is ignored for authorization and data selection. The backend resolves the owner tenant from the matching `shareCode` metadata; the cross-tenant scope is restricted to that metadata lookup, and subsequent file, key-envelope, access-count, and share-access-audit work runs in the owner tenant. Anonymous `sys_operation_log` rows use system tenant `0`; any `share_access_log` row uses the owner tenant. Both audit paths use the same canonical trusted-client IP.

The public chunk and decrypt-info routes share one tenant-independent 30-request/60-second bucket, `rate:limit:public:share-access:v2:ip:<canonical-ip>`. Changing `X-Tenant-ID`, JWT role, endpoint, `X-Forwarded-For`, or `X-Real-IP` cannot split the bucket unless the direct peer is in the configured trusted-proxy allowlist and supplies a valid chain. The first 30 combined requests may enter the controller; the current 31st-request contract remains HTTP 200 with business code `70005`. Share visibility, active/expiry state, unknown type/status, and included-file checks remain fail closed. The current model has no share-password field; password-protected shares require a separate end-to-end feature. Share writes, saving a share into a user account, and the authenticated `/api/v1/shares/{shareCode}/files/{fileHash}/chunks` and `/decrypt-info` routes still require a Bearer token.

The `.zip` routes are the canonical contract: exactly eight root entries with fixed order/timestamp/STORED metadata, a canonical `manifest.json` that hashes six evidence entries, and an `issuer-signature.jws` compact JWS over the exact manifest bytes using a dedicated Ed25519 key. Export revalidates tenant/owner authorization, original-byte `contentHash`, active manifest, storage HEAD records, the `MANIFEST_HASH` Merkle path, completed batch, and immutable contract registry. A completed batch is accepted only for `CHAIN_WRITE` with a valid 32-byte transaction hash or one of the two chain-query recovery sources with no transaction hash; its 32-byte chain root must equal the Merkle root. `contentHash`, `chainRecordId`, `manifestHash`, `cipherHash`, `merkleRoot`, and `abiFingerprint` are never interchangeable. Each entry is capped at 1 MiB and the logical payload total at 4 MiB; additional entries, nested names, and traversal paths fail closed.

The two ZIP routes share one tenant/user rate-limit bucket: 10 requests per minute for regular users and 30 for administrator or monitor roles. Each application instance runs at most eight complete proof validations concurrently, accepts at most 128 manifest chunks per proof, and enforces a 60-second total storage HEAD budget. A saturated bulkhead or expired storage budget is retryable and does not transition the proof to `INVALID`.

The signed JSON schemas are `record-platform-proof-manifest.v2`, `record-platform-proof-chunk-manifest.v2`, `record-platform-proof-merkle.v2`, `record-platform-proof-chain-receipt.v2`, and `record-platform-proof-verification-policy.v2`. Canonical JSON sorts object keys and omits null optional fields. The signed policy carries machine-readable source/transaction/root patterns, complete registry rules and fingerprint field order, lifecycle rules, and ZIP/text encoding rules. `file.hash` and ASCII `issuer-signature.jws` each end with exactly one LF byte. Successful ZIP responses expose `Content-Disposition`, `Cache-Control`, and `X-Proof-Manifest-Hash`; capacity/deadline failures return JSON 503 with `Retry-After: 5`.

The public read-only endpoint `GET /api/v1/public/proofs/{proofId}/status` returns the current `ACTIVE/REVOKED/SUPERSEDED/INVALID` state, while `GET /api/v1/public/proof-keys/{keyId}/versions/{keyVersion}` returns versioned SPKI/fingerprint material. Neither requires JWT or a tenant header, and neither exposes tenant IDs, internal file/leaf IDs, or private keys. They cumulatively share one tenant-independent, fixed 120-request/60-second bucket per canonical trusted client IP, so their 121st combined request is rejected regardless of JWT tenant or role. The default identity is the direct socket peer; forwarding headers are used only when the immediate peer matches the explicitly configured numeric trusted-proxy allowlist. `statusVersion` is a decimal JSON string. `issuedAt` is the actual first successful Ed25519 signing time, not the leaf/batch creation time, and deterministic rebuilds reuse the persisted value and JWS. A signed manifest's `issuedStatus` is restricted to `ACTIVE/SUPERSEDED`; verifiers must resolve `statusLocation`. `INVALID` is terminal and is assigned only for deterministic drift of a persisted canonical manifest, JWS, signing-key identity, or immutable issuance snapshot, with reason `immutable_snapshot_validation_failed`. Storage, Merkle, registry, receipt, or other dependency/read failures reject only that export and never mutate lifecycle state. A tenant owner or administrator may revoke; revocation is limited to 10 requests per user per 60 seconds (30 for admin/monitor), and `REVOKED` and `INVALID` cannot be restored or revoked again. The old JSON routes remain as deprecated, unsigned `proof-bundle.v1.1` compatibility endpoints and must not be treated as signed authenticity evidence.

### Admin File Audit (`/api/v1/admin/files`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/admin/files` | Get all files (paged) |
| GET | `/api/v1/admin/files/{id}` | File detail with audit info |
| PUT | `/api/v1/admin/files/{id}/status` | Update file status |
| DELETE | `/api/v1/admin/files/{id}` | Force physical file deletion |
| GET | `/api/v1/admin/files/shares` | Get all shares (paged) |
| DELETE | `/api/v1/admin/files/shares/{shareCode}` | Force cancel share |
| GET | `/api/v1/admin/files/shares/{shareCode}/logs` | Share access logs |
| GET | `/api/v1/admin/files/shares/{shareCode}/stats` | Share access stats |

### Admin Attestation Batch Production (`/api/v1/admin/attestation-batches/production`)

Both operations require the admin role and always use the authenticated current tenant.

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/admin/attestation-batches/production/trigger` | Force one bounded production run for the current tenant |
| GET | `/api/v1/admin/attestation-batches/production/status` | Get effective limits, candidate backlog, and due batch count |

### Admin Manifest Backfill (`/api/v1/admin/manifest-backfill-runs`)

All operations require the admin role and are tenant-isolated. `SCAN` creates a source snapshot; `DRY_RUN` and `APPLY` require that snapshot's external ID. Apply, sweep mark, and sweep delete remain independently feature-gated.

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/admin/manifest-backfill-runs` | Create `SCAN`, `DRY_RUN`, or `APPLY` run |
| GET | `/api/v1/admin/manifest-backfill-runs` | List bounded run history |
| GET | `/api/v1/admin/manifest-backfill-runs/{runId}` | Inspect one run |
| GET | `/api/v1/admin/manifest-backfill-runs/{runId}/items` | Cursor-list classified items (limit capped at 100) |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/pause` | Pause at a durable boundary |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/resume` | Resume the same snapshot/cursor |
| POST | `/api/v1/admin/manifest-backfill-runs/{runId}/items/{itemId}/retry` | Retry an eligible failed item |
| POST | `/api/v1/admin/manifest-backfill-runs/reference-census` | Seal current reference census evidence |
| POST | `/api/v1/admin/manifest-backfill-runs/reference-sweep/marks` | Grace-mark an exact storage object |

The default backfill worker is enabled but apply is disabled. Reference mark/delete are both disabled and use a 30-day protection window. Detailed states, classifications, and defaults are frozen in [Chunk Manifest and Legacy Governance](/en/architecture/chunk-manifest).

### Admin Key Rotation (`/api/v1/admin/key-rotation`)

All operations require the admin role and use only the authenticated tenant. Policy and run responses omit raw provider key IDs; item responses omit recipient and source/candidate envelope IDs. See the [Key Rotation Runbook](../../operations/key-rotation.md) before APPLY or external key retirement.

| Method | Endpoint | Description |
|------|------|------|
| PUT | `/api/v1/admin/key-rotation/policy` | Create or replace the bounded tenant policy |
| GET | `/api/v1/admin/key-rotation/policy` | Read the sanitized policy and retirement state |
| POST | `/api/v1/admin/key-rotation/policy/pause` | Pause future scheduling |
| POST | `/api/v1/admin/key-rotation/policy/resume` | Resume future scheduling |
| POST | `/api/v1/admin/key-rotation/policy/disable` | Disable future scheduling |
| POST | `/api/v1/admin/key-rotation/policy/retirement/acknowledge` | Record an externally completed retirement after all gates |
| POST | `/api/v1/admin/key-rotation/runs` | Idempotently start `DRY_RUN` or `APPLY` |
| GET | `/api/v1/admin/key-rotation/runs` | List bounded tenant run history |
| GET | `/api/v1/admin/key-rotation/runs/{runId}` | Read immutable target and progress counters |
| GET | `/api/v1/admin/key-rotation/runs/{runId}/items` | Cursor-page sanitized per-envelope outcomes |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/pause` | Pause one durable run |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/resume` | Resume the same cursor and attempts |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/cancel` | Stop future discovery and claims |
| POST | `/api/v1/admin/key-rotation/runs/{runId}/retry` | Requeue terminal items still classified as retryable |

### Admin Integrity Alerts (`/api/v1/admin/integrity-alerts`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/admin/integrity-alerts` | List integrity alerts (paginated, filters: `status`, `alertType`) |
| POST | `/api/v1/admin/integrity-alerts/check` | Trigger manual integrity check |
| PUT | `/api/v1/admin/integrity-alerts/{id}/acknowledge` | Acknowledge an integrity alert |
| PUT | `/api/v1/admin/integrity-alerts/{id}/resolve` | Resolve an integrity alert (with note) |

Alert responses add `severity` and bounded `evidence` while retaining existing fields. Manifest-driven `alertType` values are `MANIFEST_MISSING`, `MANIFEST_INVALID`, `OBJECT_NOT_FOUND`, `METADATA_MISMATCH`, `CONTENT_HASH_MISMATCH`, `CHAIN_NOT_FOUND`, and `CHAIN_MISMATCH`; legacy values remain readable.

### Quota (`/api/v1/files/quota`, `/api/v1/admin/quota`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/files/quota` | Get current user quota status |
| POST | `/api/v1/admin/quota/rollout/audits` | Upsert quota rollout audit record (admin) |
| GET | `/api/v1/admin/quota/rollout/audits` | Query quota rollout audit record (admin, param: `batchId`; tenant comes from auth context) |

### Public Share Page (`/api/v1/shares`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/shares/{shareCode}/info` | Get share info (public) |

### Images (`/api/v1/images`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/images/upload/avatar` | Upload avatar |
| POST | `/api/v1/images/upload/image` | Upload image |
| GET | `/api/v1/images/download/images/**` | Download image (public) |

### Friends (`/api/v1/friends`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/friends/requests` | Send friend request |
| GET | `/api/v1/friends/requests/received` | Received requests |
| GET | `/api/v1/friends/requests/sent` | Sent requests |
| PUT | `/api/v1/friends/requests/{requestId}/status` | Accept request |
| PUT | `/api/v1/friends/requests/{requestId}/status` | Reject request |
| DELETE | `/api/v1/friends/requests/{requestId}` | Cancel request |
| GET | `/api/v1/friends/requests/pending-count` | Pending request count |
| GET | `/api/v1/friends` | Friend list (paged) |
| GET | `/api/v1/friends/all` | Full friend list |
| DELETE | `/api/v1/friends/{friendId}` | Remove friend |
| PUT | `/api/v1/friends/{friendId}/remark` | Update friend remark |
| GET | `/api/v1/friends/search` | Search users |

### Friend Shares (`/api/v1/friend-shares`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/friend-shares` | Share files with friend |
| GET | `/api/v1/friend-shares/received` | Received friend shares |
| GET | `/api/v1/friend-shares/sent` | Sent friend shares |
| GET | `/api/v1/friend-shares/{shareId}` | Share detail |
| PUT | `/api/v1/friend-shares/{shareId}/read-status` | Mark as read |
| DELETE | `/api/v1/friend-shares/{shareId}` | Cancel share |
| GET | `/api/v1/friend-shares/unread-count` | Unread count |

### Conversations (`/api/v1/conversations`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/conversations` | Conversation list |
| GET | `/api/v1/conversations/{id}` | Conversation detail + messages |
| GET | `/api/v1/conversations/unread-count` | Unread conversation count |
| PUT | `/api/v1/conversations/{id}/read-status` | Mark conversation as read |
| DELETE | `/api/v1/conversations/{id}` | Delete conversation |

### Messages (`/api/v1/messages`)

| Method | Endpoint | Description |
|------|------|------|
| POST | `/api/v1/messages` | Send private message |
| GET | `/api/v1/messages/unread-count` | Total unread messages |

### Announcements (`/api/v1/announcements`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/announcements/latest` | Latest announcements |
| GET | `/api/v1/announcements` | Announcement list |
| GET | `/api/v1/announcements/{id}` | Announcement detail |
| GET | `/api/v1/announcements/unread-count` | Unread announcement count |
| PUT | `/api/v1/announcements/{id}/read-status` | Mark one announcement as read |
| PUT | `/api/v1/announcements/read-status` | Mark all announcements as read |
| GET | `/api/v1/admin/announcements` | Admin announcement list |
| POST | `/api/v1/announcements` | Publish announcement (admin) |
| PUT | `/api/v1/announcements/{id}` | Update announcement (admin) |
| DELETE | `/api/v1/announcements/{id}` | Delete announcement (admin) |

### Tickets (`/api/v1/tickets`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/tickets` | My ticket list |
| GET | `/api/v1/tickets/{id}` | Ticket detail |
| POST | `/api/v1/tickets` | Create ticket |
| PUT | `/api/v1/tickets/{id}` | Update ticket |
| POST | `/api/v1/tickets/{id}/reply` | Reply ticket |
| POST | `/api/v1/tickets/{id}/close` | Close ticket |
| POST | `/api/v1/tickets/{id}/confirm` | Confirm completion |
| GET | `/api/v1/tickets/pending-count` | Pending ticket count |
| GET | `/api/v1/tickets/unread-count` | Unread ticket count |
| GET | `/api/v1/admin/tickets` | Admin ticket list |
| PUT | `/api/v1/admin/tickets/{ticketId}/assignee` | Assign ticket (admin) |
| PUT | `/api/v1/admin/tickets/{ticketId}/status` | Update status (admin) |
| GET | `/api/v1/admin/tickets/pending-count` | Admin pending ticket count |

### Permissions (`/api/v1/system/permissions`, admin)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/permissions` | Permission tree |
| GET | `/api/v1/system/permissions/list` | Permission page list |
| GET | `/api/v1/system/permissions/modules` | Module list |
| POST | `/api/v1/system/permissions` | Create permission |
| PUT | `/api/v1/system/permissions/{id}` | Update permission |
| DELETE | `/api/v1/system/permissions/{id}` | Delete permission |
| GET | `/api/v1/system/permissions/roles/{role}` | Role permissions |
| POST | `/api/v1/system/roles/{role}/permissions` | Grant permission |
| DELETE | `/api/v1/system/roles/{role}/permissions/{permissionCode}` | Revoke permission |

### System Monitoring (`/api/v1/system`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/stats` | System statistics |
| GET | `/api/v1/system/chain-status` | Blockchain status |
| GET | `/api/v1/system/health` | System health |
| GET | `/api/v1/system/storage-capacity` | Storage capacity aggregation (cluster/node/domain) |
| GET | `/api/v1/system/monitor` | Aggregated monitoring metrics |

### System Audit (`/api/v1/system/audit`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/system/audit/overview` | Audit overview |
| GET | `/api/v1/system/audit/logs` | Audit log page (GET) |
| POST | `/api/v1/system/audit/logs/query` | Audit log page (POST) |
| GET | `/api/v1/system/audit/logs/{id}` | Audit log detail |
| POST | `/api/v1/system/audit/logs/export` | Export audit logs |
| GET | `/api/v1/system/audit/high-frequency` | High-frequency operations |
| POST | `/api/v1/system/audit/sensitive/page` | Sensitive operation page |
| GET | `/api/v1/system/audit/error-stats` | Error stats |
| GET | `/api/v1/system/audit/time-distribution` | Time distribution |
| GET | `/api/v1/system/audit/configs` | Audit configs |
| PUT | `/api/v1/system/audit/configs` | Update audit configs |
| POST | `/api/v1/system/audit/anomalies/check` | Check anomalies |
| POST | `/api/v1/system/audit/logs/backups` | Backup logs |

### SSE (`/api/v1/sse`)

| Method | Endpoint | Description |
|------|------|------|
| GET | `/api/v1/sse/connect` | Establish SSE connection (short-lived token) |
| DELETE | `/api/v1/sse/disconnect` | Disconnect |
| GET | `/api/v1/sse/status` | Connection status |

Recommended flow:

```text
1) POST /api/v1/auth/tokens/sse   (Authorization: Bearer <jwt>)
2) GET  /api/v1/sse/connect?token=<sseToken>&x-tenant-id=<tenantHint>
```

Clients capable of custom headers may use `X-Tenant-ID`; `tenantId` remains accepted as a legacy query alias for `x-tenant-id`. A missing or mismatched hint, an invalid/expired/consumed token, or a damaged token payload fails before an emitter is created. The raw one-time token is omitted from operation logs and persisted audit parameters.

Typical event types:

- `connected`
- `heartbeat`
- `message-received`
- `file-record-success`
- `file-record-failed`
- `announcement-published`
- `ticket-updated`
- `friend-request`
- `friend-accepted`
- `friend-share`
- `audit-alert`

## Unified Response Format

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

- For business error codes: `/docs/en/api/error-codes.md`
- For fuller module-level details: `/API_DOCUMENTATION.md`
