import { sha256 } from "@noble/hashes/sha2.js";
import { describe, expect, it, vi } from "vitest";
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
import {
  buildFramedAad,
  deriveFramedKeyMaterial,
  FRAMED_AEAD_AAD_SCHEMA,
  FRAMED_AEAD_FORMAT_VERSION,
  FRAMED_AEAD_SUITE,
  FRAMED_AEAD_TAG_BYTES,
  MIN_FRAME_PLAIN_BYTES,
} from "./framedAead";

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

/** 构造可控制响应声明长度和 reader cancel 行为的流式响应。 */
function responseFromChunks(
  chunks: Uint8Array[],
  declaredLength?: number,
  cancel?: (reason?: unknown) => void | Promise<void>,
  close = true,
): Response {
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      if (close) controller.close();
    },
    cancel,
  });
  return new Response(stream, {
    headers:
      declaredLength == null
        ? undefined
        : { "content-length": String(declaredLength) },
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
  nextKey: Uint8Array | null,
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
  const next = nextKey ? `\n--NEXT_KEY--\n${base64(nextKey)}` : "";
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

/** 原地修改 canonical JSON，并同步刷新 manifest 摘要。 */
function mutateCanonicalManifest(
  metadata: FileDownloadMetadataVO,
  mutate: (manifest: Record<string, unknown>) => void,
): void {
  const manifest = JSON.parse(metadata.canonicalManifestJson) as Record<
    string,
    unknown
  >;
  mutate(manifest);
  metadata.canonicalManifestJson = JSON.stringify(canonicalValue(manifest));
  metadata.manifestHash = hashManifestJson(metadata.canonicalManifestJson);
}

/** 构造单分片 NONE metadata，便于覆盖失败关闭分支。 */
function plainMetadata(bytes = new Uint8Array([1, 2])): FileDownloadMetadataVO {
  return withCanonicalManifest({
    fileId: "plain-file",
    fileHash: "plain-hash",
    fileName: "plain.bin",
    fileSize: bytes.length,
    contentType: "application/octet-stream",
    initialKey: undefined,
    manifestSchemaId: "v1",
    manifestHash: "sha256:00",
    hashAlgorithm: "SHA-256",
    encryptionAlgorithm: "NONE",
    storageBackend: "S3",
    chunkSize: Math.max(bytes.length, 1),
    totalChunks: 1,
    parts: [part(0, bytes, "u0")],
  });
}

/** 写入测试用 uint32 大端字段。 */
function writeUint32(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer).setUint32(offset, value, false);
}

/** 构造可被完整认证的一帧 framed v2 下载 fixture。 */
async function framedMetadataFixture(): Promise<{
  metadata: FileDownloadMetadataVO;
  encoded: Uint8Array;
  plaintext: Uint8Array;
}> {
  const plaintext = new TextEncoder().encode("bounded framed payload");
  const fileNonce = Uint8Array.from({ length: 16 }, (_, index) => index + 1);
  const fileDek = Uint8Array.from({ length: 32 }, (_, index) => index + 33);
  const material = deriveFramedKeyMaterial({
    fileDek,
    fileNonce,
    chunkIndex: 0,
    frameIndex: 0,
  });
  const aad = buildFramedAad({
    fileNonce,
    chunkIndex: 0,
    chunkCount: 1,
    frameIndex: 0,
    frameCount: 1,
    plainLength: plaintext.length,
    chunkPlainSize: plaintext.length,
  });
  const cryptoKey = await globalThis.crypto.subtle.importKey(
    "raw",
    material.key as unknown as BufferSource,
    { name: "AES-GCM" },
    false,
    ["encrypt"],
  );
  const ciphertext = new Uint8Array(
    await globalThis.crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv: material.nonce as unknown as BufferSource,
        additionalData: aad as unknown as BufferSource,
        tagLength: FRAMED_AEAD_TAG_BYTES * 8,
      },
      cryptoKey,
      plaintext as unknown as BufferSource,
    ),
  );
  const header = new Uint8Array(44);
  header.set(new TextEncoder().encode("RPF2"), 0);
  header[4] = FRAMED_AEAD_FORMAT_VERSION;
  header[5] = 1;
  writeUint32(header, 8, 0);
  writeUint32(header, 12, 1);
  writeUint32(header, 16, MIN_FRAME_PLAIN_BYTES);
  writeUint32(header, 20, 1);
  writeUint32(header, 24, plaintext.length);
  header.set(fileNonce, 28);
  const frameHeader = new Uint8Array(12);
  writeUint32(frameHeader, 0, 0);
  writeUint32(frameHeader, 4, plaintext.length);
  writeUint32(frameHeader, 8, ciphertext.length);
  const encoded = new Uint8Array(
    header.length + frameHeader.length + ciphertext.length,
  );
  encoded.set(header, 0);
  encoded.set(frameHeader, header.length);
  encoded.set(ciphertext, header.length + frameHeader.length);

  const encryption = {
    formatVersion: FRAMED_AEAD_FORMAT_VERSION,
    algorithmSuite: FRAMED_AEAD_SUITE,
    fileNonce: base64(fileNonce),
    framePlainSize: MIN_FRAME_PLAIN_BYTES,
    keyDerivation: "HKDF-SHA256",
    nonceDerivation: "HKDF-SHA256",
    aadSchema: FRAMED_AEAD_AAD_SCHEMA,
    tagSize: FRAMED_AEAD_TAG_BYTES,
  };
  const framedPart = {
    ...part(0, encoded, "u0"),
    plainHash: `sha256:${bytesToHex(sha256(plaintext))}`,
    plainSize: plaintext.length,
    frameCount: 1,
  };
  const metadata = withCanonicalManifest({
    fileId: "framed-file",
    fileHash: "framed-hash",
    fileName: "framed.bin",
    fileSize: plaintext.length,
    contentType: "application/octet-stream",
    initialKey: base64(fileDek),
    manifestSchemaId: "v1",
    manifestHash: "sha256:00",
    hashAlgorithm: "SHA-256",
    encryptionAlgorithm: "FRAMED_AEAD_V2",
    storageBackend: "S3",
    chunkSize: MIN_FRAME_PLAIN_BYTES,
    totalChunks: 1,
    parts: [framedPart],
    encryption,
  });
  return { metadata, encoded, plaintext };
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

  it("should authenticate a framed v2 part through the bounded dispatcher", async () => {
    const fixture = await framedMetadataFixture();
    const sink = new MemoryDownloadSink(fixture.plaintext.length, 1024);

    const metrics = await executeBoundedDownload({
      metadata: fixture.metadata,
      expectedFileHash: fixture.metadata.fileHash,
      sink,
      fetchImpl: async () => responseFor(fixture.encoded, [1, 7, 31]),
    });

    expect(metrics).toMatchObject({
      currentBufferedBytes: 0,
      framesAuthenticated: 1,
      partsCompleted: 1,
      bytesWritten: fixture.plaintext.length,
    });
    expect(Array.from(sink.getData())).toEqual(Array.from(fixture.plaintext));
  });

  it.each([
    {
      name: "unknown suite",
      expected: "不支持的 framed AEAD 下载合同",
      prepare: (metadata: FileDownloadMetadataVO) => {
        if (!metadata.encryption) throw new Error("fixture 缺少加密描述");
        metadata.encryption.algorithmSuite = "OTHER-SUITE";
      },
    },
    {
      name: "undersized frame",
      expected: "不支持的 framed AEAD 下载合同",
      prepare: (metadata: FileDownloadMetadataVO) => {
        if (!metadata.encryption) throw new Error("fixture 缺少加密描述");
        metadata.encryption.framePlainSize = 1;
      },
    },
    {
      name: "invalid file nonce",
      expected: "framed AEAD fileNonce 必须为 16 字节",
      prepare: (metadata: FileDownloadMetadataVO) => {
        if (!metadata.encryption) throw new Error("fixture 缺少加密描述");
        metadata.encryption.fileNonce = base64(new Uint8Array([1]));
      },
    },
  ])(
    "should reject framed v2 $name before fetching",
    async ({ expected, prepare }) => {
      const fixture = await framedMetadataFixture();
      prepare(fixture.metadata);
      const sink = trackingSink();
      const fetchMock = vi.fn(async () => {
        throw new Error("must not fetch");
      });

      await expect(
        executeBoundedDownload({
          metadata: fixture.metadata,
          sink,
          fetchImpl: fetchMock,
        }),
      ).rejects.toThrow(expected);
      expect(fetchMock).not.toHaveBeenCalled();
      expect(sink.aborted).toBe(true);
      expect(sink.closed).toBe(false);
    },
  );

  it("should fail closed on canonical manifest shape and binding drift", async () => {
    const cases: Array<{
      expected: string;
      prepare: (metadata: FileDownloadMetadataVO) => void;
    }> = [
      {
        expected: "缺少 canonical manifest JSON",
        prepare: (metadata) => {
          metadata.canonicalManifestJson = " ";
        },
      },
      {
        expected: "fileHash 与任务不一致",
        prepare: (metadata) => {
          metadata.fileHash = "";
        },
      },
      {
        expected: "canonical manifest JSON 无效",
        prepare: (metadata) => {
          metadata.canonicalManifestJson = "{";
          metadata.manifestHash = hashManifestJson("{");
        },
      },
      {
        expected: "canonical manifest 不是有效对象",
        prepare: (metadata) => {
          metadata.canonicalManifestJson = "[]";
          metadata.manifestHash = hashManifestJson("[]");
        },
      },
      {
        expected: "字段 schema 与 metadata 不一致",
        prepare: (metadata) => {
          mutateCanonicalManifest(metadata, (manifest) => {
            manifest.schema = "other-schema";
          });
        },
      },
      {
        expected: "字段 chunkSize 与 metadata 不一致",
        prepare: (metadata) => {
          mutateCanonicalManifest(metadata, (manifest) => {
            manifest.chunkSize = "2";
          });
        },
      },
      {
        expected: "字段 encryptionAlgorithm 不应存在",
        prepare: (metadata) => {
          metadata.encryptionAlgorithm = undefined;
        },
      },
      {
        expected: "manifest 明文总量与文件大小不一致",
        prepare: (metadata) => {
          metadata.fileSize += 1;
        },
      },
      {
        expected: "manifest 分片数量与 metadata 不一致",
        prepare: (metadata) => {
          mutateCanonicalManifest(metadata, (manifest) => {
            manifest.chunks = [];
          });
        },
      },
      {
        expected: "manifest encryption 与 metadata 不一致",
        prepare: (metadata) => {
          mutateCanonicalManifest(metadata, (manifest) => {
            manifest.encryption = {};
          });
        },
      },
      {
        expected: "字段 etag 与 metadata 不一致",
        prepare: (metadata) => {
          metadata.parts[0].etag = "etag-after-signing";
        },
      },
    ];

    for (const scenario of cases) {
      const metadata = plainMetadata();
      scenario.prepare(metadata);
      const sink = trackingSink();
      await expect(
        executeBoundedDownload({
          metadata,
          expectedFileHash: "plain-hash",
          sink,
          fetchImpl: async () => {
            throw new Error("must not fetch");
          },
        }),
      ).rejects.toThrow(scenario.expected);
      expect(sink.aborted).toBe(true);
    }

    const framedFixture = await framedMetadataFixture();
    mutateCanonicalManifest(framedFixture.metadata, (manifest) => {
      delete manifest.encryption;
    });
    await expect(
      executeBoundedDownload({
        metadata: framedFixture.metadata,
        sink: trackingSink(),
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("缺少 encryption 描述");

    const missingPlainSize = (await framedMetadataFixture()).metadata;
    delete missingPlainSize.parts[0].plainSize;
    await expect(
      executeBoundedDownload({
        metadata: missingPlainSize,
        sink: trackingSink(),
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("manifest 分片总量无效");
  });

  it("should cancel untrusted NONE responses on network and length violations", async () => {
    const oversizedChunk = new Uint8Array(1024 * 1024 + 1);
    const cases = [
      {
        metadata: plainMetadata(oversizedChunk),
        chunks: [oversizedChunk],
        declaredLength: oversizedChunk.byteLength,
        keepOpen: true,
        expected: "网络读取块超过有界下载上限",
      },
      {
        metadata: plainMetadata(new Uint8Array([1])),
        chunks: [new Uint8Array([1, 2])],
        declaredLength: undefined,
        keepOpen: true,
        expected: "超过 manifest 声明大小",
      },
      {
        metadata: plainMetadata(new Uint8Array([1, 2])),
        chunks: [new Uint8Array([1])],
        declaredLength: undefined,
        keepOpen: false,
        expected: "长度与 manifest 不一致",
      },
    ];

    for (const scenario of cases) {
      let cancelled = false;
      const response = responseFromChunks(
        scenario.chunks,
        scenario.declaredLength,
        () => {
          cancelled = true;
        },
        !scenario.keepOpen,
      );
      await expect(
        executeBoundedDownload({
          metadata: scenario.metadata,
          sink: trackingSink(),
          fetchImpl: async () => response,
        }),
      ).rejects.toThrow(scenario.expected);
      if (scenario.keepOpen) expect(cancelled).toBe(true);
    }
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

  it.each([
    "KEY",
    "key-s",
    "Secret",
    "INITIAL-Key",
    "file_DEK",
    "data-key",
    "encrypted.DATA.key",
    "Wrapped_DATA_Key",
    "decrypt-key",
    "Decryption_Key",
    "encryption.KEY",
    "file-key",
    "file.DATA.key",
    "wrapping-IV",
    "KMS_key_ID",
    "private-key",
    "secret.KEY",
  ])(
    "should reject normalized secret field %s injected into canonical manifest",
    async (secretField) => {
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
      injected.audit = { [secretField]: "secret-material" };
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
    },
  );

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
    const plaintext1 = new TextEncoder().encode("tail!");
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
    expect(new TextDecoder().decode(sink.getData())).toBe("firsttail!");
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

  it.each([
    { name: "gap", plainSizes: [4, 6] },
    { name: "overlap", plainSizes: [6, 4] },
  ])(
    "should reject legacy v1 $name ranges before fetching",
    async ({ plainSizes }) => {
      const firstCipher = new Uint8Array([1]);
      const secondCipher = new Uint8Array([2]);
      const metadata = withCanonicalManifest({
        fileId: "legacy-range-file",
        fileHash: "legacy-range-hash",
        fileName: "legacy-range.bin",
        fileSize: 10,
        contentType: "application/octet-stream",
        initialKey: base64(new Uint8Array(32)),
        manifestSchemaId: "v1",
        manifestHash: "sha256:00",
        hashAlgorithm: "SHA-256",
        encryptionAlgorithm: "AES-GCM",
        storageBackend: "S3",
        chunkSize: 5,
        totalChunks: 2,
        parts: [
          { ...part(0, firstCipher, "u0"), plainSize: plainSizes[0] },
          { ...part(1, secondCipher, "u1"), plainSize: plainSizes[1] },
        ],
      });
      const sink = trackingSink();
      const fetchMock = vi.fn(async () => {
        throw new Error("must not fetch");
      });

      await expect(
        executeBoundedDownload({ metadata, sink, fetchImpl: fetchMock }),
      ).rejects.toThrow("明文范围不连续或尺寸不一致");
      expect(fetchMock).not.toHaveBeenCalled();
      expect(sink.aborted).toBe(true);
    },
  );

  it("should reject legacy v1 range overflow before fetching", async () => {
    const metadata = withCanonicalManifest({
      fileId: "legacy-overflow-file",
      fileHash: "legacy-overflow-hash",
      fileName: "legacy-overflow.bin",
      fileSize: Number.MAX_SAFE_INTEGER,
      contentType: "application/octet-stream",
      initialKey: base64(new Uint8Array(32)),
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "AES-GCM",
      storageBackend: "S3",
      chunkSize: Number.MAX_SAFE_INTEGER,
      totalChunks: 2,
      parts: [
        {
          ...part(0, new Uint8Array([1]), "u0"),
          plainSize: Number.MAX_SAFE_INTEGER,
        },
        { ...part(1, new Uint8Array([2]), "u1"), plainSize: 1 },
      ],
    });
    const sink = trackingSink();
    const fetchMock = vi.fn(async () => {
      throw new Error("must not fetch");
    });

    await expect(
      executeBoundedDownload({ metadata, sink, fetchImpl: fetchMock }),
    ).rejects.toThrow("明文范围无效");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(sink.aborted).toBe(true);
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

  it("should retry transient object failures but fail fast on expired URLs", async () => {
    vi.useFakeTimers();
    try {
      const bytes = new Uint8Array([7]);
      let attempts = 0;
      let transientBodiesCancelled = 0;
      const download = executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: false,
        sink: new MemoryDownloadSink(1, 64),
        fetchImpl: async () => {
          attempts++;
          if (attempts < 3) {
            return new Response(
              new ReadableStream({
                cancel() {
                  transientBodiesCancelled++;
                },
              }),
              { status: 503 },
            );
          }
          return responseFor(bytes);
        },
      });

      await vi.runAllTimersAsync();
      await expect(download).resolves.toMatchObject({ bytesWritten: 1 });
      expect(attempts).toBe(3);
      expect(transientBodiesCancelled).toBe(2);
    } finally {
      vi.useRealTimers();
    }

    let expiredAttempts = 0;
    let expiredBodyCancelled = 0;
    await expect(
      executeLegacyFallbackDownload({
        urls: ["expired"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: false,
        sink: trackingSink(),
        fetchImpl: async () => {
          expiredAttempts++;
          return new Response(
            new ReadableStream({
              cancel() {
                expiredBodyCancelled++;
              },
            }),
            { status: 403 },
          );
        },
      }),
    ).rejects.toThrow("下载地址已过期");
    expect(expiredAttempts).toBe(1);
    expect(expiredBodyCancelled).toBe(1);

    let notFoundAttempts = 0;
    let notFoundBodyCancelled = 0;
    await expect(
      executeLegacyFallbackDownload({
        urls: ["not-found"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: false,
        sink: trackingSink(),
        fetchImpl: async () => {
          notFoundAttempts++;
          return new Response(
            new ReadableStream({
              cancel() {
                notFoundBodyCancelled++;
              },
            }),
            { status: 404 },
          );
        },
      }),
    ).rejects.toThrow("分片 1 下载失败: 404");
    expect(notFoundAttempts).toBe(1);
    expect(notFoundBodyCancelled).toBe(1);
  });

  it("should enforce legacy fallback response bounds before decryption or commit", async () => {
    const plainCases = [
      {
        fileSize: 1,
        response: () => responseFromChunks([new Uint8Array([1])], 2),
        expected: "超过文件声明大小",
      },
      {
        fileSize: 1,
        response: () =>
          responseFromChunks([new Uint8Array([1, 2])], undefined, undefined),
        expected: "超过文件声明大小",
      },
      {
        fileSize: 2,
        response: () => responseFromChunks([new Uint8Array([1])], 2),
        expected: "长度与响应声明不一致",
      },
      {
        fileSize: 1,
        response: () => responseFromChunks([]),
        expected: "历史明文分片为空",
      },
    ];
    for (const scenario of plainCases) {
      await expect(
        executeLegacyFallbackDownload({
          urls: ["u0"],
          fileSize: scenario.fileSize,
          totalChunks: 1,
          encrypted: false,
          sink: trackingSink(),
          fetchImpl: async () => scenario.response(),
        }),
      ).rejects.toThrow(scenario.expected);
    }

    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: true,
        sink: trackingSink(),
        fetchImpl: async () => responseFromChunks([new Uint8Array([1])], 2),
        initialKey: base64(new Uint8Array(32)),
      }),
    ).rejects.toThrow("长度与响应声明不一致");

    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: true,
        sink: trackingSink(),
        fetchImpl: async () => responseFromChunks([new Uint8Array([1, 2])], 1),
        initialKey: base64(new Uint8Array(32)),
      }),
    ).rejects.toThrow("超过响应声明大小");
  });

  it("should reject legacy key and random-access contract violations", async () => {
    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: true,
        sink: trackingSink(),
      }),
    ).rejects.toThrow("缺少 initialKey");

    const sequentialSink: DownloadSink = {
      ...trackingSink(),
      supportsRandomAccess: false,
    };
    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: true,
        initialKey: base64(new Uint8Array(32)),
        sink: sequentialSink,
      }),
    ).rejects.toThrow("支持随机写入");

    const metadata = withCanonicalManifest({
      fileId: "legacy-file",
      fileHash: "legacy-hash",
      fileName: "legacy.bin",
      fileSize: 1,
      contentType: "application/octet-stream",
      initialKey: undefined,
      manifestSchemaId: "v1",
      manifestHash: "sha256:00",
      hashAlgorithm: "SHA-256",
      encryptionAlgorithm: "AES-GCM",
      storageBackend: "S3",
      chunkSize: 1,
      totalChunks: 1,
      parts: [part(0, new Uint8Array([1]), "u0")],
    });
    await expect(
      executeBoundedDownload({
        metadata,
        sink: trackingSink(),
        fetchImpl: async () => {
          throw new Error("must not fetch");
        },
      }),
    ).rejects.toThrow("缺少 initialKey");
  });

  it("should reject leaked buffers or mismatched write accounting before close", async () => {
    const bytes = new Uint8Array([1]);
    const leakedMetrics = new DownloadMetricsTracker();
    leakedMetrics.acquire(1);
    await expect(
      executeBoundedDownload({
        metadata: plainMetadata(bytes),
        sink: new MemoryDownloadSink(1, 64),
        metrics: leakedMetrics,
        fetchImpl: async () => responseFor(bytes),
      }),
    ).rejects.toThrow("下载缓冲区未释放");

    const mismatchedMetrics = new DownloadMetricsTracker();
    mismatchedMetrics.wrote(1);
    await expect(
      executeLegacyFallbackDownload({
        urls: ["u0"],
        fileSize: 1,
        totalChunks: 1,
        encrypted: false,
        sink: new MemoryDownloadSink(1, 64),
        metrics: mismatchedMetrics,
        fetchImpl: async () => responseFor(bytes),
      }),
    ).rejects.toThrow("写入长度不一致");
  });

  it.each([
    [{ encryption: { formatVersion: 0 } }, "NONE"],
    [{ encryptionAlgorithm: " none " }, "NONE"],
    [
      {
        encryptionAlgorithm: "framed_aead_v2",
        encryption: { formatVersion: 2 },
      },
      "FRAMED_V2",
    ],
    [
      { encryptionAlgorithm: "CHACHA20", encryption: { formatVersion: 1 } },
      "LEGACY_V1",
    ],
  ])("should resolve supported download contracts", (metadata, expected) => {
    expect(resolveDownloadFormat(metadata as never)).toBe(expected);
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
