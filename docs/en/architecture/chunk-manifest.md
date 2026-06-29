# Chunk Manifest Contract

`cn.flying.chunk-manifest.v1` is the shared metadata model for large-file transfer, integrity checks, proof generation, storage migration, and partial repair.

The manifest is metadata only. It records ordered chunk references and hashes, but it does not store file bytes in the backend database.

## Canonical Payload

The persisted manifest hash is calculated over canonical JSON without the `manifestHash` field:

```json
{
  "schema": "cn.flying.chunk-manifest.v1",
  "fileHash": "sha256:...",
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

`ChunkManifestCanonicalizer` sorts chunks by `index`, requires contiguous indexes starting at `0`, and calculates `sha256:<lowercase-hex>` from the canonical JSON bytes.

## Persistence

`file_chunk_manifest` stores manifest-level metadata:

- `tenant_id`, `file_id`, and optional `file_version`
- `schema_id`, `file_hash`, `manifest_hash`, and `hash_algorithm`
- `chunk_size`, `chunk_count`, `total_size`
- optional `merkle_root`, `encryption_algorithm`, `storage_backend`
- `manifest_json` canonical payload

`file_chunk_manifest_item` stores ordered chunk entries:

- `manifest_id`, `file_id`, `chunk_index`
- `plain_hash`, `cipher_hash`, `size`
- `storage_path`, optional `storage_backend`, `etag`, `checksum_algorithm`

Existing files can have no manifest. `ChunkManifestService.findActiveManifest(...)` returns an empty result in that case so legacy upload/download paths keep working until P2-1 and P2-2 wire the model into transfer flows.
