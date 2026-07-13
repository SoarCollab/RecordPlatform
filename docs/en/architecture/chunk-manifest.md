# Chunk Manifest Contract

`cn.flying.chunk-manifest.v1` is the shared metadata model for large-file transfer, integrity checks, proof generation, storage migration, and partial repair.

The manifest is metadata only. It records ordered chunk references and hashes, but it does not store file bytes in the backend database.

## Canonical Payload

The persisted manifest hash is calculated over canonical JSON without the `manifestHash` field:

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

`ChunkManifestCanonicalizer` sorts chunks by `index`, requires contiguous indexes starting at `0`, and calculates `sha256:<lowercase-hex>` from the canonical JSON bytes.

The v1 name `fileHash` is retained for compatibility with the current file table. Its value mirrors `file.fileHash`, which is the blockchain record identifier in the current backend; it is **not** an object-content digest. Use chunk `plainHash` for plaintext evidence, chunk `cipherHash` for stored-object evidence, and `manifestHash` for the ordered canonical manifest digest.

`merkleRoot` is optional in v1 and no chunk-Merkle construction algorithm is defined by this schema. When present it is covered by `manifestHash`. Integrity checks therefore use the canonical `manifestHash` as the defined proof of the ordered chunk set and only validate the optional root's SHA-256 shape; they do not invent a Merkle algorithm.

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

Manifest-backed download metadata requires an active manifest. `GET /api/v1/files/hash/{fileHash}/download-metadata` returns `FILE_RECORD_ERROR` when the file has no active `cn.flying.chunk-manifest.v1` record instead of falling back to URL-only metadata.

Direct multipart upload completion persists the active manifest after validating every staging object against its declared `cipherHash`. The current direct-upload contract stores unencrypted object bytes, so `plainHash` and `cipherHash` must match and the manifest `encryption_algorithm` is `NONE`.

Integrity checks batch-load active manifest headers and chunks. Lightweight and medium checks use `storagePath`/`cipherHash` for every `HeadObject`; heavy checks download only configured sampled chunks and recompute their `cipherHash`. A missing active manifest is reported as `MANIFEST_MISSING` and is not treated as object corruption or silently checked through the legacy chain-record field.
