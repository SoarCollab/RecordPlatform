# P0/P1/P2/P3 Delivery Evidence

The P0-P2 implementation evidence on this page was frozen from the last pre-documentation exact-main baseline, `main@0e091261233e7430ce5b904ad84d33569de910a1`, on 2026-07-27. P2-5 is accepted separately only when its later merge commit passes exact-main checks and Pages deployment. P3 evidence is leaf-scoped: each row names the exact merge commit, Test Suite, documentation run, and Pages deployment that actually ran for that leaf. The page does not imply that one later documentation-only SHA re-executed every historical gate.

## Evidence rules

- **Implementation evidence** points to production code, configuration, Flyway migrations, OpenAPI contracts, or generated types.
- **Automated evidence** points to deterministic unit, integration, browser, provider, or CI gates.
- **Runtime evidence** is limited to an exact commit, workflow run, environment fingerprint, and retained artifact. A previous leaf's result is not silently reused for a later commit.
- `ROADMAP.md` is the only canonical roadmap. Historical Trellis tasks and pull requests are execution evidence, not competing roadmap files.

## P0 stability foundation

| Delivered capability | Implementation evidence | Automated evidence | Boundary |
| --- | --- | --- | --- |
| Tenant-aware cache isolation | `TenantKeyUtils` and the file query/write cache annotations bind entries and evictions to `tenantId:userId` | cache proxy and cross-tenant service tests | Authorization still comes from the authenticated tenant context, never a cache key supplied by the caller |
| External-ID-safe administration | file administration controllers decode external IDs with `IdUtils` before internal persistence calls | invalid, empty, and authorization controller tests | An obfuscated external ID is never accepted as a raw database primary key |
| Recoverable upload cleanup | persisted upload state records tenant, user, SUID, paths, retry state, and finalization claims | cleanup/restart/finalization integration tests | Failures retain a bounded manual recovery entry instead of reporting success |
| Manifest-driven integrity checks | `IntegrityCheckService`, active chunk manifests, storage HEAD evidence, and `V1.17.0__manifest_backfill_governance.sql` | three-tier integrity and alert controller tests | `file.fileHash` is a chain record identifier; it is not substituted for `contentHash`, `plainHash`, or `cipherHash` |

## P1 proof productization

| Delivered capability | Implementation evidence | Automated evidence | Boundary |
| --- | --- | --- | --- |
| Recoverable batch submission | persisted candidate/batch state machine, query-before/after-write recovery, and bounded production admission | chain fault, retry exhaustion, concurrency, and exact-main CI | Unknown chain existence never becomes `false`; exhausted writes enter query-only recovery/manual terminal state |
| Contract identity governance | immutable registry entries bind chain, address, semantic version, ABI fingerprint, runtime bytecode, and deployment receipt | FISCO/Besu registry, runtime-code, receipt, nonce, and rollback tests | Self-reported contract name/version alone is insufficient |
| Signed proof ZIP v2 | deterministic eight-entry STORED archive, canonical JSON, compact JWS `EdDSA`, immutable issuance snapshot, public key/status endpoints | archive boundary, signature, lifecycle, rate-limit, audit-isolation, and concurrency tests | `proof-bundle.v1.1` JSON remains deprecated and unsigned; its backend verifier is compatibility-only |
| Independent verifier | `platform-verifier` SDK, CLI, and standalone Web service share `DefaultProofVerifier` | valid/tampered/revoked/offline/SSRF/ZIP-boundary fixtures | `VALID` requires all local checks, a trusted Ed25519 key, current `ACTIVE` status, and a matching live chain root. Resolver unavailability yields `INDETERMINATE`, not `VALID` |
| Public boundary hardening | trusted-client IP resolver, tenant-independent public buckets, authoritative SSE short token, owner-tenant public share lookup | negative tenant/header/proxy/rate-limit and audit isolation tests | Caller-controlled tenant or forwarding headers never become proof/share/SSE authority |

## P2 large-file and high-concurrency completion

| Leaf | Delivered contract | Primary evidence | Acceptance evidence |
| --- | --- | --- | --- |
| P2-1 native promotion | Direct create -> presigned staging PUT -> complete; same endpoint uses conditional server-side copy, cross endpoint uses bounded streaming; receipt precedes repair and cleanup | `StorageProperties.DirectUploadConfig`, promotion/receipt/intent/tombstone services, backend finalization claims | real MinIO/Redis integration, restart/race tests, and 96 MiB constrained-heap gate |
| P2-Q1 frontend dependency remediation | complete frontend development graph blocks new High/Critical advisories without weakening tests | frontend workspace/lockfile and CI audit gate | targeted Dependabot alerts fixed, not dismissed |
| P2-Q2 Jackson runtime remediation | Jackson 2.x/3.x patched consistently across API/backend/FISCO/storage/verifier | five Maven dependency trees and JSON/log compatibility tests | runtime advisories fixed and stale versions absent from refreshed dependency evidence |
| P2-1A test dependency remediation | storage test scope pins safe Commons Compress/Lang3 and enforces minimum versions | storage POM/enforcer and test/runtime dependency trees | real container tests pass; production runtime remains free of the test-only dependencies |
| P2-2 bounded downloads | owned/public/authenticated share downloads use one manifest reader; `NONE`, legacy v1, and framed AEAD v2 are explicit paths; supported browsers write through a transactional File System Access sink; one identity-fenced 401/403 refresh may resume the current part | `FileDownloadMetadataBuilder`, generated OpenAPI aliases, `boundedDownloader.ts`, `downloadSink.ts`, `fileSize.ts`, framed v2 migration/fixtures | Chromium 64/256/512 MiB gates keep application buffers non-growing, prove stable refresh recovery, and abort drift/tamper/truncation/cancel/sink failures before commit |
| P2-3 manifest governance | tenant-scoped `SCAN`/`DRY_RUN`/`APPLY`, insert-only publication, deterministic classification, reference census and independently gated sweep | admin controller/services and `V1.17.0__manifest_backfill_governance.sql` | transaction/concurrency/crash recovery/security tests; no guessing from map order, ETag, name, or chain record ID |
| P2-4 load/fault evidence | direct-path k6 and mandatory MinIO/Redis/Toxiproxy matrix cover upload, download, quorum, degraded repair, timeout, races, and cleanup | `tools/k6/direct-path.js`, `perf-smoke.yml`, storage fault/load ITs | exact-main Test Suite `30209115456`; Linux amd64/Java 21.0.11 constrained smoke completed 8/8 files with zero lifecycle residue; the immutable metrics are in the [retained report](/evidence/direct-upload-load-smoke-30209115456.json) |
| P2-Q3 docs advisory remediation | docs workspace pins DOMPurify 3.4.12 and PostCSS 8.5.18 | `docs/pnpm-workspace.yaml` and reproducible lockfile | PR #315, exact-main docs deploy, docs low-level audit zero; alerts #588/#590 fixed without dismissal |
| P2-5 online documentation | this bilingual evidence matrix and synchronized architecture/API/operations pages | VitePress routes, nav, canonical roadmap, OpenAPI and consistency script | accepted only after docs build, consistency checks, exact-main deploy, and live Pages verification |

## P3 enterprise key governance

| Leaf | Exact merge and implementation evidence | Automated evidence | Exact runtime/Pages evidence | Boundary |
| --- | --- | --- | --- | --- |
| P3-1 wrapping provider and external KMS | [PR #317](https://github.com/SoarCollab/RecordPlatform/pull/317), exact `main@cfe9b9e54eefa01246dbddda7ab5a4c27717a3dc`; `FileKeyEnvelopeService`, `VaultTransitKeyWrappingProvider`, and `V1.18.0__key_wrapping_provider_metadata.sql` | `VaultTransitKeyWrappingProviderIT`, `KeyWrappingProviderMigrationIT`, provider/configuration unit tests | [Test Suite `30222751986`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30222751986), [Deploy Documentation `30222751977`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30222751977), Pages deployment `5614564147` | Historical envelopes route only by persisted provider/contract. Production Vault availability and permissions remain environment-owned and fail closed. |
| P3-2 automated envelope rotation | [PR #318](https://github.com/SoarCollab/RecordPlatform/pull/318), exact `main@a4ba5acf3864fd341219a7382d13b2cd30d3afde`; rotation policy/run/item services, admin controller, and `V1.19.0__automated_key_rotation.sql` | `KeyRotationMigrationIT` plus scheduler, claim, crash-recovery, audit, metrics, and controller tests | [Test Suite `30235985313`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30235985313), [Deploy Documentation `30235985338`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30235985338), Pages deployment `5616938788` | The application confirms external retirement but never disables or deletes provider keys. A run cannot expand beyond its frozen candidate snapshot. |
| P3-3 runtime crypto agility | [PR #319](https://github.com/SoarCollab/RecordPlatform/pull/319), exact `main@c0bd8076994ce0cb3bf98a3ff0f722c60ea84a4c`; `CryptoSuiteRegistry`, tenant policy/admin APIs, and `V1.20.0__runtime_crypto_agility.sql` | `RuntimeCryptoAgilityMigrationIT`, registry/policy/configuration tests, persisted-identity dispatch regressions | [Test Suite `30245935210`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30245935210), [Deploy Documentation `30245935295`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30245935295), Pages deployment `5618660915` | ML-DSA/ML-KEM catalog entries remain experimental/unimplemented and cannot be selected for production writes. Historical artifacts never fall back to a new default. |
| P3-4 plaintext-key exposure reduction | [PR #320](https://github.com/SoarCollab/RecordPlatform/pull/320), exact `main@7f9d639f3395269735e9efeb3dbea4e9e025d412`; `FileKeyGrantService`, authenticated/public consume controllers, generated OpenAPI types, and frontend memory-only download flow | `FileKeyGrantRedisIT`, controller/rate-limit/audit isolation, frontend lifecycle, non-extractable-key, OpenAPI and browser E2E tests | [Test Suite `30267453969`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30267453969), [Deploy Documentation `30267454033`](https://github.com/SoarCollab/RecordPlatform/actions/runs/30267454033), Pages deployment `5622671802` | `grant-v1` is the default and responses are `no-store`; `plaintext-v0` is explicit, disabled by default, and deadline-bound. Browser cleanup reduces ordinary exposure but is not a physical-memory-forensics guarantee. |

Each row above is historical evidence for that exact leaf. Later commits inherit implementation only through normal Git ancestry; they must run their own applicable checks and must not claim that skipped or non-applicable jobs re-executed.

## Current transfer limits and defaults

| Contract | Current value |
| --- | --- |
| Direct-upload file / part ceiling | 4 GiB / 100 MiB |
| Promotion stream buffer | 64 KiB default; effective range 8 KiB to 1 MiB |
| Promotion transfer deadline | 300 seconds default; maximum 1,800 seconds |
| Staging retention | at least 48 hours |
| Browser memory fallback | hard ceiling 64 MiB |
| Download classifications | streaming recommended at 500 MiB; very large at 2 GiB; absolute maximum 100 GiB |
| Bounded reader | at most 10,000 parts, 1 MiB network chunk, 3 fetch attempts; 401/403 refresh metadata immediately, only 5xx is retried |
| Legacy v1 ciphertext part | approximately 80 MiB plus 4 KiB compatibility overhead |

These values are configuration and product limits, not throughput promises.

## Dependency advisory boundary

The P2-Q3 statement is a historical, lockfile-bound docs audit result, not a permanent repository-wide zero. The bounded snapshot `main@85a57ae847423308cf60683c6fd299d51a1650f1` at 2026-08-10 14:51 CST had 3 open alerts, all Maven / Medium `org.apache.commons:commons-lang3`, in `platform-verifier/sdk`, `platform-verifier/cli-verifier`, and `platform-verifier/web-verifier`. The current Spring Boot BOM resolves 3.17.0 while the first patched version is 3.18.0. P6 supply-chain maintenance owns a separate Maven compatibility upgrade and revalidation by 2026-09-30 or before the next release, whichever is earlier. The current value must always be read from the [Dependabot live view](https://github.com/SoarCollab/RecordPlatform/security/dependabot); this dated snapshot must not be presented as current after the graph or registry changes.

## Release verification

For any later change, repeat the relevant tests on the candidate commit, merge normally, fetch the resulting merge SHA, and then wait for workflows that actually apply to that exact `origin/main`. Documentation is considered online only after the Pages deployment for the same SHA succeeds and the key English/Chinese routes return the updated content.
