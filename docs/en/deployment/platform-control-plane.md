# Platform Control Plane Operations

The platform control plane manages tenant lifecycle, tenant-user metadata, quotas and a small safe configuration registry. It uses a separate platform identity and operation history.

## Enable the platform identity

Apply the additive `V1.23.0` migration and follow [Platform Administrator Bootstrap](./platform-administrator-bootstrap.md). Keep the one-time bootstrap disabled after provisioning. Platform authentication must be explicitly enabled with `PLATFORM_IDENTITY_ENABLED=true`; enabling the feature does not promote an existing tenant administrator.

The migration preserves existing configuration IDs and widens `sys_audit_config.id` from INT to BIGINT so missing safe configuration rows can be recreated with application-generated Snowflake IDs. Automatic ID allocation remains disabled; apply the forward migration before using the new configuration writer.

All platform API requests carry:

```http
Authorization: Bearer <platform-token>
X-Tenant-ID: 0
```

The token must have the single role `platform_admin`, `scope=platform` and identity tenant `0`. The server derives its fixed capabilities from that identity. Tenant permission definitions and role mappings cannot grant capabilities from the reserved `platform:` namespace. An ordinary administrator in tenant `0` retains ordinary tenant scope.

Read `GET /api/v1/platform/session` for the platform identity and fixed capabilities. Tenant targets use the external entity ID returned by the API, while user targets use external user IDs. Never replace the identity tenant header with a target tenant ID. Raw numeric IDs and the wrong external ID type are rejected.

The [API index](../api/index.md) lists all routes. Platform APIs do not expose impersonation, file contents, downloads, raw Nacos/environment values, credentials, container controls or hard deletion.

## Use the platform administration interface

Choose the platform administration entry on the login page and sign in with a provisioned platform account. Platform identities enter `/platform`; tenant accounts enter the existing workspace. Ordinary administrators in legacy tenant `0` continue to use the tenant entry. The login form distinguishes these two identity contexts; management targets are selected inside the platform pages.

| Page | Purpose |
|---|---|
| `/platform` | Inspect tenant and user counts and shared service status |
| `/platform/tenants` | Search tenants, create a tenant and open its details |
| `/platform/tenants/{tenantId}` | Change tenant name/status, manage members and invitations, and inspect tenant usage and quota |
| `/platform/users` | Search user metadata by tenant, role and status |
| `/platform/resources` | Inspect shared service health and a selected tenant's usage and quota |
| `/platform/configuration` | Inspect and change allowlisted global configuration |
| `/platform/audit` | Find platform operations and inspect their outcomes, reasons and change summaries |

After creating a tenant, invite its first administrator from the detail page. The invitation link is delivered by email; the administration interface shows invitation status without exposing the one-time capability or a temporary password. The public acceptance page can preserve an existing signed-in account. Continuing with that account opens the workspace appropriate to its identity.

For a mutation, enter the proposed change and reason, then review the target and change before confirming. Duplicate submissions are disabled while the request is pending. If the result is uncertain, retry the same operation. If another operation has changed the resource, reload it and review the current state before confirming again. A permission denial does not sign out the account. Unavailable measurements appear as unknown or failed, never as measured zero.

The platform interface has independent navigation without tenant files, global file search or a download queue. Global configuration in the tenant audit page is read-only; configuration changes belong in the platform interface.

## Create and administer a tenant

1. Send `POST /api/v1/platform/tenants` with a new UUID `Idempotency-Key`, a reason, immutable lower-case `code` and display `name`. Codes are 2–64 characters, begin with a letter and contain only letters, digits and hyphens. Optional `maxStorageBytes` and `maxFileCount` use the existing quota defaults when absent.
2. Save the returned `resourceId` as the tenant ID. Tenant creation atomically creates its initial quota and creates no account or password.
3. Send `POST /api/v1/platform/tenants/{tenantId}/invitations` with a different operation key and `email`, `role: "admin"`, `expiresInHours` (1–168) and `reason` to invite the first administrator. The capability is delivered through the existing email flow; APIs and operation logs do not return it.
4. Manage members through that exact tenant's user routes. Existing last-active-administrator and self-operation rules still apply. Platform administrator accounts are absent from tenant member searches and cannot be targeted through member commands.
5. Before updating tenant metadata or status, read its current `version`. Send that value as `expectedVersion`. Changing tenant status invalidates old authorization state and active tenant sessions. System tenant `0` cannot be disabled.

Each page accepts `pageNum >= 1` and `pageSize` from 1 through 100. Keyword filters are bounded to 100 characters. User projections contain only approved account metadata; they do not include password hashes, authorization versions, verification state or file paths.

## Quotas and safe configuration

Read the tenant quota before changing it. The response includes effective limits, usage, policy source, enforcement mode and override version. A new tenant's initial override has version `0`; an absent override also reports `0`, and its first update creates version `1`. Limits are nonnegative integers no greater than `9007199254740991`. Updating limits preserves the existing quota rollout and `SHADOW`/`ENFORCE` policy.

The global configuration registry permits these integer keys only:

| Key | Minimum | Maximum |
|---|---:|---:|
| `HIGH_FREQ_THRESHOLD` | 1 | 1,000,000 |
| `FAILED_LOGIN_THRESHOLD` | 1 | 10,000 |
| `ERROR_RATE_THRESHOLD` | 1 | 100 |
| `LOG_RETENTION_DAYS` | 1 | 3,650 |

Configuration responses identify the global database source, online mutability, restart requirement and current version. Malformed stored values appear as `value: null` with `state: UNAVAILABLE`; the raw stored text is never returned. Read the version, correct the value within its documented bounds, and submit a new logical command.

`PUT /api/v1/system/audit/configs` is retired and always denied, including for legacy tenant-zero administrators. Its GET counterpart remains available to authorized tenant audit readers as a validated numeric projection. All configuration changes use `PUT /api/v1/platform/configuration/{key}` with a reason, operation key and expected version.

## Retry and conflict handling

Every write requires a canonical UUID `Idempotency-Key` and a nonblank reason of at most 255 characters. Generate one key for one logical command; keep it with the complete original payload until the result is known.

| Outcome | Client action |
|---|---|
| Successful response | Save `operationId`, `resourceId` and any returned `version` |
| Lost response or transport uncertainty | Retry the exact payload and original key |
| `50022 PLATFORM_VERSION_CONFLICT` | Read the resource again and review changes; a newly confirmed action needs a new key |
| `50023 PLATFORM_IDEMPOTENCY_CONFLICT` | The key already belongs to a different payload; do not silently replace its meaning |
| `50024 PLATFORM_OPERATION_IN_PROGRESS` | Preserve the original key; do not submit an equivalent command under a new key |
| Recorded terminal failure | The same key replays that failure; a corrected logical action uses a new key |
| Missing `Idempotency-Key` | HTTP 400 with `10004 PARAM_NOT_COMPLETE`; supply a key before attempting the command |

Authentication/authorization failures use the existing 401/403 boundary. Other validated business failures retain the project's `Result.code` contract; HTTP 200 alone does not mean a mutation succeeded.

A short independent transaction records the operation claim. The business mutation and success audit commit together. A rolled-back command records a sanitized failure in a separate transaction. An audit-store failure does not become a successful mutation response.

## Audit and interruption recovery

`GET /api/v1/platform/audit` and `GET /api/v1/platform/audit/{operationId}` require the platform audit capability. Platform mutation history resides in system-owned `platform_operation_log`; generic tenant operation/member logs do not duplicate it. Reasons and before/after summaries are bounded and masked. Logs contain stable failure codes rather than arbitrary exception messages or request bodies.

A process interruption can leave a durable `PROCESSING` record. There is deliberately no automatic claim recycling or forced-success endpoint. An operator must establish that the original process is no longer executing, inspect the operation and current resource state, and correlate any invitation delivery or session invalidation before deciding on a separately reviewed recovery action. Retain the original claim and diagnostic evidence. Do not delete the record, rewrite its fingerprint or change its state merely to make a retry succeed.

For a rollback, use a reviewed rollback build that preserves denial of the legacy global configuration writer and the safe read projection. An unmodified earlier build reopens `PUT /api/v1/system/audit/configs` to tenant administrators/monitors and is not an acceptable rollback target. If an emergency rollback relies on an ingress block, verify that the exact legacy write route is denied through every reachable backend path before serving traffic; the read projection must remain safe as well. Retain the additive migration, existing tenant/quota/configuration rows and platform operation history. Do not reverse schema history or re-run one-time bootstrap.

## Measurement and verification

The overview reports measured tenant and account metadata counts. Tenant usage includes only the explicit tenant's business audit and completed attestation counts. Shared health returns only `database`, `redis`, `blockchain` and `storage` status values. Missing or failed measurements remain unknown or produce a failed request; they are never invented as zero or healthy.

Required CI must execute the real MySQL/Redis platform suite (20 cases) and forward-migration suite (2 cases), each with zero skipped, failed or errored cases. Unit/HTTP checks cover all 24 platform routes and the role/capability, typed-ID, version, logging and safe-configuration boundaries. A local machine without Docker cannot supply the real-database evidence.
