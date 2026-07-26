# Chunk Manifest and Legacy Governance

`cn.flying.chunk-manifest.v1` is the shared metadata contract for large-file transfer, integrity checks, proof generation, storage migration, and partial repair. The manifest contains metadata only: ordered object references and hashes are persisted, while file bytes remain in object storage.

## Canonical Contract

The persisted `manifestHash` is SHA-256 over canonical JSON without the `manifestHash` field:

```json
{
  "schema": "cn.flying.chunk-manifest.v1",
  "fileHash": "chain-record-id",
  "hashAlgorithm": "SHA-256",
  "chunkSize": 83886080,
  "totalSize": 167772160,
  "storageBackend": "S3",
  "chunks": [
    {
      "index": 0,
      "plainHash": "sha256:...",
      "cipherHash": "sha256:...",
      "size": 83886080,
      "storagePath": "storage/tenant/1/chunk/...",
      "storageBackend": "S3",
      "checksumAlgorithm": "SHA-256"
    }
  ]
}
```

`ChunkManifestCanonicalizer` sorts chunks by `index`, requires contiguous indexes beginning at `0`, and emits `sha256:<lowercase-hex>` from the canonical UTF-8 JSON bytes.

The v1 field name `fileHash` is retained for compatibility with the file table. Its value mirrors `file.fileHash`, which is the blockchain record identifier; it is **not** an object-content digest. Use:

- chunk `plainHash` for plaintext evidence;
- chunk `cipherHash` for stored-object evidence;
- `manifestHash` for the ordered canonical manifest digest;
- signed proof `contentHash`/`file.hash` for the complete original-file digest.

`merkleRoot` is optional in v1, and this schema does not define a chunk-Merkle algorithm. When present, the value is covered by `manifestHash`; integrity checks validate its SHA-256 shape but do not invent a construction algorithm.

## Persistence and Active Slot

`file_chunk_manifest` stores the manifest header and canonical JSON. `file_chunk_manifest_item` stores ordered chunk entries. Migration `V1.17.0` adds a tenant/file active-slot constraint so at most one undeleted active manifest exists for one file record. A version is represented by its stable file record; `file_version` remains evidence metadata rather than part of the unique key.

Direct multipart completion persists the active manifest after validating every staging object against its declared `cipherHash`. The current direct-upload contract stores unencrypted object bytes, so `plainHash` and `cipherHash` must match and `encryptionAlgorithm` is `NONE`.

Integrity checks batch-load active headers and chunks. Lightweight and medium checks use `storagePath` and `cipherHash` for each `HeadObject`; heavy checks download only configured sampled chunks and recompute `cipherHash`. A missing manifest is classified as `MANIFEST_MISSING`, not object corruption.

## Missing-Manifest Machine Contract

`GET /api/v1/files/hash/{fileHash}/download-metadata` never silently substitutes legacy URL-only metadata for an owned file that needs an active manifest. Successful metadata includes:

- `canonicalManifestJson`
- `manifestStatus`
- `manifestClassification`
- `manifestErrorCode`
- `legacyDownloadAllowed`

An active manifest returns `manifestStatus=ACTIVE`, `manifestClassification=ALREADY_MANIFEST`, and `legacyDownloadAllowed=false`. A missing manifest returns business code `FILE_RECORD_ERROR`; the standard `ErrorPayload` carries a structured `ManifestErrorDetail` at `data.detail`. Before a governance item exists, that detail is:

```json
{
  "manifestStatus": "REUPLOAD_REQUIRED",
  "manifestClassification": "UNCLASSIFIED",
  "manifestErrorCode": "MISSING_MANIFEST_UNCLASSIFIED",
  "legacyDownloadAllowed": false
}
```

After classification, `data.detail` reflects the latest governance item. Clients must branch on these fields rather than parsing `message`. `legacyDownloadAllowed` is an explicit policy output; it is never inferred from a missing row.

## Backfill Modes and Classification

An administrator creates a durable run in one of three modes:

| Mode | Behavior |
| --- | --- |
| `SCAN` | Discover and classify candidates without generating or applying manifests. |
| `DRY_RUN` | Produce a reviewable snapshot and proposed manifests without changing the active slot. |
| `APPLY` | Apply only eligible proposals after the explicit apply gate is enabled. |

Run states are `PLANNED`, `SCANNING`, `SNAPSHOT_READY`, `APPLYING`, `PAUSED`, `COMPLETED`, and `FAILED`. Item states are `PENDING`, `RUNNING`, `BACKFILLED`, `REUPLOAD_REQUIRED`, `UNRECOVERABLE`, `FAILED`, and `IGNORED`.

Every candidate receives one classification:

| Classification | Meaning |
| --- | --- |
| `ALREADY_MANIFEST` | A valid active manifest already exists. |
| `BACKFILLABLE` | Existing evidence is sufficient to construct and verify a canonical manifest. |
| `REUPLOAD_REQUIRED` | Trustworthy chunk evidence cannot be reconstructed; the owner must upload again. |
| `UNRECOVERABLE` | Required source data is permanently absent or contradictory. |
| `FAILED` | Processing failed and may be retried within the bounded attempt policy. |
| `IGNORED` | The record is outside the selected governance scope. |

The worker uses pages of 100 records, claims at most 20 items, allows three item attempts, and leases a claim for 120 seconds.

## Safety Defaults

Backfill and destructive sweep are fail-closed by default:

| Setting | Default |
| --- | ---: |
| Backfill worker enabled | `true` |
| Apply enabled | `false` |
| Run lease | 300 seconds |
| Worker delay | 5 seconds |
| Sweep mark enabled | `false` |
| Sweep delete enabled | `false` |
| Sweep protection window | 30 days |
| Sweep batch size | 20 (hard cap 100) |
| Sweep lease | 120 seconds (minimum 30) |
| Sweep worker delay | 60 seconds |

`APPLY` mode does not bypass `apply-enabled=false`. Mark and delete are separate gates, and a deletion candidate remains protected by the time window and durable ledger.

## Administrative API

All endpoints require an administrator and are rooted at `/api/v1/admin/manifest-backfill-runs`:

- `POST /` — create a run;
- `GET /` and `GET /{runId}` — list and inspect runs;
- `GET /{runId}/items` — cursor-list items by status, classification, or reason;
- `POST /{runId}/pause` and `POST /{runId}/resume` — control worker admission;
- `POST /{runId}/items/{itemId}/retry` — retry an eligible failed item;
- `POST /reference-census` — take a durable reference census;
- `POST /reference-sweep/marks` — mark candidates after census and protection checks.

Use the detailed request/response contract in [API Documentation](/en/api/). Production rollout starts with `SCAN`, reviews the snapshot, exercises `DRY_RUN`, and enables apply or sweep gates only after an approved rollback window.
