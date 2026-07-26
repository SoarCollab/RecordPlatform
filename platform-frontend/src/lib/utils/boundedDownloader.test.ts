import { sha256 } from "@noble/hashes/sha2.js";
import { describe, expect, it } from "vitest";
import type { FileDownloadMetadataVO } from "$api/types";

import {
  executeBoundedDownload,
  executeLegacyFallbackDownload,
  LEGACY_MAX_CIPHER_PART_BYTES,
  resolveDownloadFormat,
} from "./boundedDownloader";
import { bytesToHex } from "./downloadIntegrity";
import { DownloadMetricsTracker } from "./downloadMetrics";
import { MemoryDownloadSink, type DownloadSink } from "./downloadSink";

/** 构造可观察 abort/close 语义的测试 sink。 */
function trackingSink(): DownloadSink & {
  aborted: boolean;
  closed: boolean;
} {
  const state = { aborted: false, closed: false };
  return {
    supportsRandomAccess: true,
    aborted: state.aborted,
    closed: state.closed,
    async write() {},
    async writeAt() {},
    async close() {
      state.closed = true;
      this.closed = true;
    },
    async abort() {
      state.aborted = true;
      this.aborted = true;
    },
  };
}

function base64(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes));
}

function base64url(bytes: Uint8Array): string {
  return base64(bytes)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function responseFor(bytes: Uint8Array, sizes = [1, 5, 2]): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      let offset = 0;
      let index = 0;
      while (offset < bytes.length) {
        const size = Math.min(
          sizes[index++ % sizes.length],
          bytes.length - offset,
        );
        controller.enqueue(bytes.slice(offset, offset + size));
        offset += size;
      }
      controller.close();
    },
  });
  return new Response(stream, {
    headers: { "content-length": String(bytes.length) },
  });
}

function part(index: number, bytes: Uint8Array, url: string) {
  const digest = `sha256:${bytesToHex(sha256(bytes))}`;
  return {
    index,
    size: bytes.length,
    downloadUrl: url,
    expiresAtEpochSeconds: 2_000_000_000,
    storagePath: `part/${index}`,
    plainHash: digest,
    cipherHash: digest,
    checksumAlgorithm: "SHA-256",
  };
}

/** 按后端 ObjectMapper 的字母序和 NON_NULL 规则构造 canonical manifest。 */
function canonicalValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .filter(([, entry]) => entry !== null && entry !== undefined)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, entry]) => [key, canonicalValue(entry)]),
  );
}

/** 为下载 metadata fixture 计算与后端一致的 canonical JSON/hash。 */
function withCanonicalManifest(
  metadata: Omit<
    FileDownloadMetadataVO,
    "canonicalManifestJson" | "manifestHash"
  > & {
    manifestHash?: string;
    initialKey?: string | undefined;
  },
): FileDownloadMetadataVO {
  const format = metadata.encryption?.formatVersion;
  const totalSize = metadata.parts.reduce(
    (sum, current) =>
      sum + (format === 2 ? (current.plainSize ?? 0) : current.size),
    0,
  );
  const payload = canonicalValue({
    schema: metadata.manifestSchemaId,
    fileHash: metadata.fileHash,
    hashAlgorithm: metadata.hashAlgorithm,
    chunkSize: metadata.chunkSize,
    totalSize,
    encryptionAlgorithm: metadata.encryptionAlgorithm,
    storageBackend: metadata.storageBackend,
    encryption: metadata.encryption,
    chunks: metadata.parts.map((current) => ({
      index: current.index,
      plainHash: current.plainHash,
      cipherHash: current.cipherHash,
      size: current.size,
      storagePath: current.storagePath,
      storageBackend: current.storageBackend ?? metadata.storageBackend,
      etag: current.etag,
      checksumAlgorithm: current.checksumAlgorithm ?? "SHA-256",
      plainSize: current.plainSize,
      frameCount: current.frameCount,
    })),
  });
  const canonicalManifestJson = JSON.stringify(payload);
  return {
    ...metadata,
    manifestHash: `sha256:${bytesToHex(
      sha256(new TextEncoder().encode(canonicalManifestJson)),
    )}`,
    canonicalManifestJson,
  };
}

/** 重新计算测试中被篡改 canonical JSON 的摘要。 */
function hashManifestJson(value: string): string {
  return `sha256:${bytesToHex(sha256(new TextEncoder().encode(value)))}`;
}

async function encryptLegacyChunk(
  plaintext: Uint8Array,
  key: Uint8Array,
  nextKey: Uint8Array,
): Promise<Uint8Array> {
  const iv = Uint8Array.from({ length: 12 }, (_, index) => index + 11);
  const cryptoKey = await globalThis.crypto.subtle.importKey(
    "raw",
    key as unknown as BufferSource,
    { name: "AES-GCM" },
    false,
    ["encrypt"],
  );
  const ciphertext = new Uint8Array(
    await globalThis.crypto.subtle.encrypt(
      { name: "AES-GCM", iv: iv as unknown as BufferSource },
      cryptoKey,
      plaintext as unknown as BufferSource,
    ),
  );
  const header = new Uint8Array([0x52, 0x50, 1, 1]);
  const hash = `\n--HASH--\n${base64url(sha256(plaintext))}`;
  const next = `\n--NEXT_KEY--\n${base64(nextKey)}`;
  const encoded = new Uint8Array(
    header.length + iv.length + ciphertext.length + hash.length + next.length,
  );
  let offset = 0;
  encoded.set(header, offset);
  offset += header.length;
  encoded.set(iv, offset);
  offset += iv.length;
  encoded.set(ciphertext, offset);
  offset += ciphertext.length;
  encoded.set(new TextEncoder().encode(hash), offset);
  offset += hash.length;
  encoded.set(new TextEncoder().encode(next), offset);
  return encoded;
}

describe("boundedDownloader", () => {
  it("should stream NONE parts with length and hash verification", async () => {
    const first = new TextEncoder().encode("hello ");
    const second = new TextEncoder().encode("world");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "hello.txt",
      fileSize: first.length + second.length,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: first.length,
      totalChunks: 2,
      parts: [part(0, first, "u0"), part(1, second, "u1")],
    });
    const sink = new MemoryDownloadSink(metadata.fileSize, 64 * 1024 * 1024);
    const responses = new Map([
      ["u0", responseFor(first)],
      ["u1", responseFor(second)],
    ]);
    const metrics = await executeBoundedDownload({
      metadata,
      sink,
      metrics: new DownloadMetricsTracker(),
      fetchImpl: async (url) => responses.get(String(url))!,
    });
    expect(metrics.bytesWritten).toBe(metadata.fileSize);
    expect(new TextDecoder().decode(sink.getData())).toBe("hello world");
  });

  it("should reject reordered metadata parts instead of sorting them", async () => {
    const first = new TextEncoder().encode("a");
    const second = new TextEncoder().encode("b");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "reordered.txt",
      fileSize: 2,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: 1,
      totalChunks: 2,
      parts: [part(0, first, "u0"), part(1, second, "u1")],
    });
    metadata.parts = [metadata.parts[1], metadata.parts[0]];
    const sink = trackingSink();

    await expect(
      executeBoundedDownload({
        metadata,
        sink,
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("分片顺序");
    expect(sink.aborted).toBe(true);
  });

  it("should bind canonical chunk storage evidence and reject an etag drift", async () => {
    const bytes = new TextEncoder().encode("etag-bound");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "etag.txt",
      fileSize: bytes.length,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: bytes.length,
      totalChunks: 1,
      parts: [{ ...part(0, bytes, "u0"), etag: "etag-a" }],
    });
    metadata.parts[0].etag = "etag-b";
    const sink = trackingSink();

    await expect(
      executeBoundedDownload({
        metadata,
        sink,
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("etag");
    expect(sink.aborted).toBe(true);
  });

  it("should reject case-insensitive secret fields injected into canonical manifest", async () => {
    const bytes = new TextEncoder().encode("secret-check");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "secret.txt",
      fileSize: bytes.length,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: bytes.length,
      totalChunks: 1,
      parts: [part(0, bytes, "u0")],
    });
    const injected = JSON.parse(metadata.canonicalManifestJson) as Record<
      string,
      unknown
    >;
    injected.audit = { Wrapped_DATA_Key: "secret-material" };
    const canonicalManifestJson = JSON.stringify(canonicalValue(injected));
    metadata.canonicalManifestJson = canonicalManifestJson;
    metadata.manifestHash = hashManifestJson(canonicalManifestJson);
    const sink = trackingSink();

    await expect(
      executeBoundedDownload({
        metadata,
        sink,
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("密钥材料");
    expect(sink.aborted).toBe(true);
  });

  it("should abort a bounded sink when cancellation wins before close", async () => {
    const bytes = new TextEncoder().encode("cancel-before-close");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "cancel.txt",
      fileSize: bytes.length,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: bytes.length,
      totalChunks: 1,
      parts: [part(0, bytes, "u0")],
    });
    const controller = new AbortController();
    const sink = trackingSink();

    await expect(
      executeBoundedDownload({
        metadata,
        sink,
        signal: controller.signal,
        fetchImpl: async () => responseFor(bytes),
        onPartComplete: () => controller.abort(),
      }),
    ).rejects.toThrow("Download cancelled");
    expect(sink.aborted).toBe(true);
    expect(sink.closed).toBe(false);
  });

  it("should abort a legacy sink when cancellation wins before close", async () => {
    const bytes = new TextEncoder().encode("legacy-cancel");
    const controller = new AbortController();
    const sink = trackingSink();

    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: bytes.length,
        totalChunks: 1,
        encrypted: false,
        sink,
        signal: controller.signal,
        fetchImpl: async () => responseFor(bytes),
        onPartComplete: () => controller.abort(),
      }),
    ).rejects.toThrow("Download cancelled");
    expect(sink.aborted).toBe(true);
    expect(sink.closed).toBe(false);
  });

  it("should abort an empty legacy download when already cancelled", async () => {
    const controller = new AbortController();
    controller.abort();
    const sink = trackingSink();

    await expect(
      executeLegacyFallbackDownload({
        urls: [],
        fileSize: 0,
        totalChunks: 0,
        encrypted: false,
        sink,
        signal: controller.signal,
      }),
    ).rejects.toThrow("Download cancelled");
    expect(sink.aborted).toBe(true);
    expect(sink.closed).toBe(false);
  });

  it("should preserve the v1 ring-key order while writing last part by offset", async () => {
    const plaintext0 = new TextEncoder().encode("first");
    const plaintext1 = new TextEncoder().encode("second!");
    const key0 = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
    const key1 = Uint8Array.from({ length: 32 }, (_, index) => index + 33);
    const cipher0 = await encryptLegacyChunk(plaintext0, key0, key1);
    const cipher1 = await encryptLegacyChunk(plaintext1, key1, key0);
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "file-hash",
      fileName: "legacy.txt",
      fileSize: plaintext0.length + plaintext1.length,
      contentType: "text/plain",
      initialKey: base64(key1),
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "AES-GCM",
      storageBackend: "S3",
      chunkSize: plaintext0.length,
      totalChunks: 2,
      parts: [
        {
          ...part(0, cipher0, "u0"),
          plainHash: `sha256:${bytesToHex(sha256(plaintext0))}`,
          plainSize: plaintext0.length,
        },
        {
          ...part(1, cipher1, "u1"),
          plainHash: `sha256:${bytesToHex(sha256(plaintext1))}`,
          plainSize: plaintext1.length,
        },
      ],
    });
    const sink = new MemoryDownloadSink(metadata.fileSize, 64 * 1024 * 1024);
    const responses = new Map([
      ["u0", responseFor(cipher0, [3, 1, 9])],
      ["u1", responseFor(cipher1, [2, 4, 1])],
    ]);
    await executeBoundedDownload({
      metadata,
      sink,
      fetchImpl: async (url) => responses.get(String(url))!,
    });
    expect(new TextDecoder().decode(sink.getData())).toBe("firstsecond!");
  });

  it("should reject legacy parts above the hard cap before fetching", async () => {
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "h",
      fileName: "x",
      fileSize: 1,
      contentType: "application/octet-stream",
      initialKey: base64(new Uint8Array(32)),
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "AES-GCM",
      storageBackend: "S3",
      chunkSize: 1,
      totalChunks: 1,
      parts: [
        {
          ...part(0, new Uint8Array([1]), "u0"),
          size: LEGACY_MAX_CIPHER_PART_BYTES + 1,
        },
      ],
    });
    const sink = new MemoryDownloadSink(1, 64);
    const fetchMock = async () => {
      throw new Error("must not fetch");
    };
    await expect(
      executeBoundedDownload({ metadata, sink, fetchImpl: fetchMock }),
    ).rejects.toThrow("兼容读取上限");
  });

  it("should stream no-manifest plain parts without buffering the full file", async () => {
    const first = new TextEncoder().encode("legacy ");
    const second = new TextEncoder().encode("plain");
    const sink = new MemoryDownloadSink(first.length + second.length, 64);
    const responses = new Map([
      ["u0", responseFor(first, [2, 1])],
      ["u1", responseFor(second, [1, 3])],
    ]);
    const metrics = await executeLegacyFallbackDownload({
      urls: ["u0", "u1"],
      fileSize: first.length + second.length,
      totalChunks: 2,
      encrypted: false,
      sink,
      fetchImpl: async (url) => responses.get(String(url))!,
    });
    expect(metrics.currentBufferedBytes).toBe(0);
    expect(metrics.partsCompleted).toBe(2);
    expect(new TextDecoder().decode(sink.getData())).toBe("legacy plain");
  });

  it("should decrypt no-manifest v1 parts with a bounded per-part reader", async () => {
    const plaintext0 = new TextEncoder().encode("alpha");
    const plaintext1 = new TextEncoder().encode("omega!");
    const key0 = Uint8Array.from({ length: 32 }, (_, index) => index + 3);
    const key1 = Uint8Array.from({ length: 32 }, (_, index) => index + 37);
    const cipher0 = await encryptLegacyChunk(plaintext0, key0, key1);
    const cipher1 = await encryptLegacyChunk(plaintext1, key1, key0);
    const sink = new MemoryDownloadSink(
      plaintext0.length + plaintext1.length,
      64,
    );
    const responses = new Map([
      ["u0", responseFor(cipher0, [3, 5, 2])],
      ["u1", responseFor(cipher1, [1, 4, 7])],
    ]);
    const metrics = await executeLegacyFallbackDownload({
      urls: ["u0", "u1"],
      fileSize: plaintext0.length + plaintext1.length,
      totalChunks: 2,
      initialKey: base64(key1),
      encrypted: true,
      sink,
      fetchImpl: async (url) => responses.get(String(url))!,
    });
    expect(metrics.currentBufferedBytes).toBe(0);
    expect(metrics.partsCompleted).toBe(2);
    expect(new TextDecoder().decode(sink.getData())).toBe("alphaomega!");
  });

  it("should reject a no-manifest v1 Content-Length above the legacy cap", async () => {
    const sink = new MemoryDownloadSink(1, 64);
    const response = new Response(new Uint8Array([1]), {
      headers: {
        "content-length": String(LEGACY_MAX_CIPHER_PART_BYTES + 1),
      },
    });
    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        initialKey: base64(new Uint8Array(32)),
        encrypted: true,
        sink,
        fetchImpl: async () => response,
      }),
    ).rejects.toThrow("兼容读取上限");
  });

  it("should reject NONE plainSize drift before reading the body", async () => {
    const bytes = new TextEncoder().encode("plain");
    const metadata = withCanonicalManifest({
      fileId: "f",
      fileHash: "h",
      fileName: "plain.txt",
      fileSize: bytes.length,
      contentType: "text/plain",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "NONE",
      storageBackend: "S3",
      chunkSize: bytes.length,
      totalChunks: 1,
      parts: [{ ...part(0, bytes, "u0"), plainSize: bytes.length - 1 }],
    });
    await expect(
      executeBoundedDownload({
        metadata,
        sink: new MemoryDownloadSink(bytes.length, 64),
        fetchImpl: async () => responseFor(bytes),
      }),
    ).rejects.toThrow("明文尺寸与对象尺寸不一致");
  });

  it("should abort the sink when manifest validation fails before fetching", async () => {
    const sink = trackingSink();
    await expect(
      executeBoundedDownload({
        metadata: {
          fileId: "f",
          fileHash: "h",
          fileName: "invalid.bin",
          fileSize: 1,
          contentType: "application/octet-stream",
          initialKey: undefined,
          manifestSchemaId: "v1",
          manifestHash: "sha256:00",
          canonicalManifestJson: "{}",
          hashAlgorithm: "SHA-256",
          encryptionAlgorithm: "NONE",
          storageBackend: "S3",
          chunkSize: 1,
          totalChunks: 0,
          parts: [],
        },
        sink,
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("文件或分片数量无效");
    expect(sink.aborted).toBe(true);
    expect(sink.closed).toBe(false);
  });

  it("should abort the sink when legacy fallback parameters are invalid", async () => {
    const sink = trackingSink();
    await expect(
      executeLegacyFallbackDownload({
        urls: [],
        fileSize: 1,
        totalChunks: 0,
        encrypted: false,
        sink,
      }),
    ).rejects.toThrow("历史下载缺少分片 URL");
    expect(sink.aborted).toBe(true);
    expect(sink.closed).toBe(false);
  });

  it("should reject unknown format versions", () => {
    expect(() =>
      resolveDownloadFormat({
        encryptionAlgorithm: "AES-GCM",
        encryption: { formatVersion: 3 },
      } as never),
    ).toThrow("不支持的下载格式版本");
  });

  it("should reject unknown algorithms and algorithm/version conflicts", () => {
    expect(() =>
      resolveDownloadFormat({
        encryptionAlgorithm: "UNKNOWN-AEAD",
      } as never),
    ).toThrow("不支持的下载加密算法");
    expect(() =>
      resolveDownloadFormat({
        encryptionAlgorithm: "AES-GCM",
        encryption: { formatVersion: 0 },
      } as never),
    ).toThrow("下载加密算法与 formatVersion 冲突");
    expect(() =>
      resolveDownloadFormat({
        encryptionAlgorithm: "FRAMED_AEAD_V2",
        encryption: { formatVersion: 1 },
      } as never),
    ).toThrow("下载加密算法与 formatVersion 冲突");
    expect(() =>
      resolveDownloadFormat({
        encryptionAlgorithm: "AES-GCM",
        encryption: { formatVersion: 2 },
      } as never),
    ).toThrow("下载加密算法与 formatVersion 冲突");
  });
});
