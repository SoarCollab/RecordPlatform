import type {
  FileDownloadMetadataVO,
  FileDownloadPartVO,
} from "../../src/lib/api/types/files";
import {
  buildFramedAad,
  deriveFramedKeyMaterial,
  FRAMED_AEAD_AAD_SCHEMA,
  FRAMED_AEAD_FORMAT_VERSION,
  FRAMED_AEAD_FRAME_HEADER_BYTES,
  FRAMED_AEAD_HEADER_BYTES,
  FRAMED_AEAD_SUITE,
  FRAMED_AEAD_TAG_BYTES,
} from "../../src/lib/utils/framedAead";
import {
  bytesToHex,
  createSha256,
  decodeBase64,
} from "../../src/lib/utils/downloadIntegrity";
import type { DownloadMemoryFailure, DownloadMemoryRunOptions } from "./types";

export const FRAME_PLAIN_SIZE = 1024 * 1024;
export const DOWNLOAD_SIZES_MIB = [64, 256, 512] as const;
export const FILE_NONCE_BASE64 = "AQIDBAUGBwgJCgsMDQ4PEA==";
export const FILE_DEK_BASE64 = "oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8=";
const CROSS_FILE_NONCE_BASE64 = "EBESExQVFhcYGRobHB0eHw==";
const WRONG_FILE_DEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

const NETWORK_CHUNK_SIZES = [131_071, 524_287, 1_048_573, 262_147] as const;

interface FixtureProfile {
  sizeBytes: number;
  frameCount: number;
  encodedSize: number;
  plainHash: string;
  cipherHash: string;
}

/** 递归排序 manifest 键并按 Jackson NON_NULL 规则移除空值。 */
function canonicalizeManifestValue(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(canonicalizeManifestValue);
  }
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value)
      .filter(([, entry]) => entry !== null && entry !== undefined)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, entry]) => [key, canonicalizeManifestValue(entry)]),
  );
}

/** 生成与后端 canonicalizer 一致的 UTF-8 manifest JSON 和 SHA-256。 */
function createCanonicalManifest(
  options: DownloadMemoryRunOptions,
  profile: FixtureProfile,
  part: FileDownloadPartVO,
  encryption: FileDownloadMetadataVO["encryption"],
): { json: string; hash: string } {
  const payload = canonicalizeManifestValue({
    schema: "chunk-manifest.v1",
    fileHash: profile.plainHash,
    hashAlgorithm: "SHA-256",
    chunkSize: profile.sizeBytes,
    totalSize: profile.sizeBytes,
    encryptionAlgorithm:
      options.format === "FRAMED_V2" ? "FRAMED_AEAD_V2" : "NONE",
    storageBackend: "OPFS",
    encryption,
    chunks: [
      {
        index: part.index,
        plainHash: part.plainHash,
        cipherHash: part.cipherHash,
        size: part.size,
        storagePath: part.storagePath,
        storageBackend: part.storageBackend ?? "OPFS",
        checksumAlgorithm: part.checksumAlgorithm ?? "SHA-256",
        plainSize: part.plainSize,
        frameCount: part.frameCount,
      },
    ],
  });
  const json = JSON.stringify(payload);
  const digest = createSha256();
  digest.update(new TextEncoder().encode(json));
  return { json, hash: `sha256:${bytesToHex(digest.digest())}` };
}

const FIXTURE_PROFILES: Record<
  (typeof DOWNLOAD_SIZES_MIB)[number],
  FixtureProfile
> = {
  64: {
    sizeBytes: 67_108_864,
    frameCount: 64,
    encodedSize: 67_110_700,
    plainHash:
      "sha256:281e519df3077b557c6b03f5da83c4e8d397219259615dd7c3308f89cae8f2a6",
    cipherHash:
      "sha256:636b3dafe61a9c414c4a2529680ef634d5a9483e791476aa97a9e88cd1f6a1dd",
  },
  256: {
    sizeBytes: 268_435_456,
    frameCount: 256,
    encodedSize: 268_442_668,
    plainHash:
      "sha256:486cc817b95d853d3c357ff283b204c0144bd255e73fe2deb1389493b257e3c0",
    cipherHash:
      "sha256:9925607532083f8393c870d241f5860abac747fed3cab37e6b374dbe68b8dad9",
  },
  512: {
    sizeBytes: 536_870_912,
    frameCount: 512,
    encodedSize: 536_885_292,
    plainHash:
      "sha256:c047731a3c134f3d34286d608e9c173027d50f43ab9d2064f3c360939977e908",
    cipherHash:
      "sha256:c49e684216df495873c758d8099b548a734a73a1cb8e5f10673f9e6430672516",
  },
};

export interface SyntheticStreamState {
  emittedBytes: number;
  cancelled: boolean;
  cancelReason: string | null;
}

export interface SyntheticResponse {
  response: Response;
  state: SyntheticStreamState;
}

/** 返回固定大小档位的合成下载合同，避免测试依赖后端或真实对象存储。 */
export function getFixtureProfile(
  sizeMiB: DownloadMemoryRunOptions["sizeMiB"],
): FixtureProfile {
  return FIXTURE_PROFILES[sizeMiB];
}

/** 构造 deterministic 明文，使大文件测试不需要预分配完整文件。 */
export function createPlainBytes(offset: number, length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  for (let index = 0; index < length; index++) {
    bytes[index] = (offset + index) & 0xff;
  }
  return bytes;
}

/** 构造不携带明文 key 的 NONE 或 framed v2 metadata，并绑定固定 hash/尺寸证据。 */
export function createDownloadMetadata(
  options: DownloadMemoryRunOptions,
): FileDownloadMetadataVO {
  const profile = getFixtureProfile(options.sizeMiB);
  const isFramed = options.format === "FRAMED_V2";
  const part: FileDownloadPartVO = {
    index: 0,
    size: isFramed ? profile.encodedSize : profile.sizeBytes,
    downloadUrl: `https://download-memory.test/${options.format.toLowerCase()}/${options.sizeMiB}`,
    expiresAtEpochSeconds: 4_000_000_000,
    storagePath: `fixtures/${options.format.toLowerCase()}/${options.sizeMiB}`,
    storageBackend: "OPFS",
    plainHash: profile.plainHash,
    cipherHash: isFramed ? profile.cipherHash : profile.plainHash,
    checksumAlgorithm: "SHA-256",
    plainSize: profile.sizeBytes,
    frameCount: isFramed ? profile.frameCount : undefined,
  };
  const encryption = isFramed
    ? {
        formatVersion: FRAMED_AEAD_FORMAT_VERSION,
        algorithmSuite: FRAMED_AEAD_SUITE,
        fileNonce:
          options.failure === "cross-file"
            ? CROSS_FILE_NONCE_BASE64
            : FILE_NONCE_BASE64,
        framePlainSize: FRAME_PLAIN_SIZE,
        keyDerivation: "HKDF-SHA256",
        nonceDerivation: "HKDF-SHA256",
        aadSchema: FRAMED_AEAD_AAD_SCHEMA,
        tagSize: FRAMED_AEAD_TAG_BYTES,
      }
    : undefined;
  const manifest = createCanonicalManifest(options, profile, part, encryption);
  return {
    fileId: `playwright-${options.format}-${options.sizeMiB}`,
    fileHash: profile.plainHash,
    fileName: `download-memory-${options.format}-${options.sizeMiB}.bin`,
    fileSize: profile.sizeBytes,
    contentType: "application/octet-stream",
    initialKey: undefined,
    manifestSchemaId: "chunk-manifest.v1",
    manifestHash: manifest.hash,
    canonicalManifestJson: manifest.json,
    manifestStatus: "ACTIVE",
    manifestClassification: "ALREADY_MANIFEST",
    legacyDownloadAllowed: false,
    hashAlgorithm: "SHA-256",
    encryptionAlgorithm: isFramed ? "FRAMED_AEAD_V2" : "NONE",
    storageBackend: "OPFS",
    chunkSize: profile.sizeBytes,
    totalChunks: 1,
    accessIdentity: {
      accessKind: "OWNER",
      identityHash: `sha256:${"a".repeat(64)}`,
      fileVersion: 1,
      manifestHash: manifest.hash,
      algorithmSuite: isFramed ? FRAMED_AEAD_SUITE : "NONE",
    },
    parts: [part],
    encryption,
  };
}

/** 模拟 grant-v1 的即时消费，只在 framed 下载开始前返回本次执行所需 DEK。 */
export function consumeSyntheticDownloadKeyGrant(
  options: DownloadMemoryRunOptions,
): string | undefined {
  if (options.format !== "FRAMED_V2") return undefined;
  return options.failure === "wrong-key"
    ? WRONG_FILE_DEK_BASE64
    : FILE_DEK_BASE64;
}

/** 写入 framed wire format 使用的无符号大端整数。 */
function writeUint32(bytes: Uint8Array, offset: number, value: number): void {
  new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).setUint32(
    offset,
    value,
    false,
  );
}

/** 创建单个 framed chunk header。 */
function createChunkHeader(
  profile: FixtureProfile,
  failure?: DownloadMemoryFailure,
): Uint8Array {
  const header = new Uint8Array(FRAMED_AEAD_HEADER_BYTES);
  header.set(new TextEncoder().encode("RPF2"), 0);
  header[4] = FRAMED_AEAD_FORMAT_VERSION;
  header[5] = 1;
  writeUint32(header, 8, 0);
  writeUint32(header, 12, 1);
  writeUint32(header, 16, FRAME_PLAIN_SIZE);
  writeUint32(header, 20, profile.frameCount);
  writeUint32(header, 24, profile.sizeBytes);
  header.set(
    decodeBase64(
      failure === "cross-file" ? CROSS_FILE_NONCE_BASE64 : FILE_NONCE_BASE64,
    ),
    28,
  );
  return header;
}

/** 将输出位置映射为实际 frame 坐标，用于构造重排和重复 wire 序列。 */
function resolveWireFrameIndex(
  framePosition: number,
  failure?: DownloadMemoryFailure,
): number {
  if (failure === "reorder") {
    if (framePosition === 0) return 1;
    if (framePosition === 1) return 0;
  }
  if (failure === "duplicate" && framePosition === 1) return 0;
  return framePosition;
}

/** 对一个明文 frame 生成与 Java writer 合同一致的 AES-GCM 密文。 */
async function encryptFrame(
  frameIndex: number,
  profile: FixtureProfile,
  failure?: DownloadMemoryFailure,
): Promise<Uint8Array> {
  const plaintext = createPlainBytes(
    frameIndex * FRAME_PLAIN_SIZE,
    FRAME_PLAIN_SIZE,
  );
  const fileNonce = decodeBase64(FILE_NONCE_BASE64);
  const material = deriveFramedKeyMaterial({
    fileDek: decodeBase64(FILE_DEK_BASE64),
    fileNonce,
    chunkIndex: 0,
    frameIndex,
  });
  const aad = buildFramedAad({
    fileNonce,
    chunkIndex: 0,
    chunkCount: 1,
    frameIndex,
    frameCount: profile.frameCount,
    plainLength: plaintext.byteLength,
    chunkPlainSize: profile.sizeBytes,
  });
  const key = await globalThis.crypto.subtle.importKey(
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
      key,
      plaintext as unknown as BufferSource,
    ),
  );
  if (failure === "tamper" && frameIndex === 1) {
    ciphertext[32] ^= 0xff;
  }
  return ciphertext;
}

/** 创建 framed frame header。 */
function createFrameHeader(
  frameIndex: number,
  cipherLength: number,
): Uint8Array {
  const header = new Uint8Array(FRAMED_AEAD_FRAME_HEADER_BYTES);
  writeUint32(header, 0, frameIndex);
  writeUint32(header, 4, FRAME_PLAIN_SIZE);
  writeUint32(header, 8, cipherLength);
  return header;
}

/** 创建按任意网络边界切分的真实 ReadableStream Response。 */
export function createSyntheticResponse(
  options: DownloadMemoryRunOptions,
): SyntheticResponse {
  const profile = getFixtureProfile(options.sizeMiB);
  const state: SyntheticStreamState = {
    emittedBytes: 0,
    cancelled: false,
    cancelReason: null,
  };
  const truncateAt =
    options.failure === "truncate" ? profile.encodedSize - 37 : null;
  let networkChunkIndex = 0;
  let pending: Uint8Array | null = null;
  let pendingOffset = 0;
  let nextFrameIndex = -1;
  let framePhase: "header" | "ciphertext" = "header";
  let finished = false;

  /** 取出下一个 framed wire segment，保持只保留当前 frame。 */
  async function ensureFramedSegment(): Promise<void> {
    if (pending && pendingOffset < pending.byteLength) return;
    pending = null;
    pendingOffset = 0;
    if (nextFrameIndex === -1) {
      pending = createChunkHeader(profile, options.failure);
      nextFrameIndex = 0;
      return;
    }
    if (nextFrameIndex >= profile.frameCount) {
      finished = true;
      return;
    }
    if (framePhase === "header") {
      const wireFrameIndex = resolveWireFrameIndex(
        nextFrameIndex,
        options.failure,
      );
      pending = createFrameHeader(
        wireFrameIndex,
        FRAME_PLAIN_SIZE + FRAMED_AEAD_TAG_BYTES,
      );
      framePhase = "ciphertext";
      return;
    }
    pending = await encryptFrame(
      resolveWireFrameIndex(nextFrameIndex, options.failure),
      profile,
      options.failure,
    );
    nextFrameIndex += 1;
    framePhase = "header";
  }

  /** 生成一个有界网络 chunk，并在截断场景中提前结束流。 */
  async function nextNetworkChunk(): Promise<Uint8Array | null> {
    if (finished) return null;
    const target =
      NETWORK_CHUNK_SIZES[networkChunkIndex++ % NETWORK_CHUNK_SIZES.length];
    if (options.format === "NONE") {
      const remaining = profile.sizeBytes - state.emittedBytes;
      if (remaining <= 0) {
        finished = true;
        return null;
      }
      const length = Math.min(
        target,
        remaining,
        truncateAt == null ? remaining : truncateAt - state.emittedBytes,
      );
      if (length <= 0) {
        finished = true;
        return null;
      }
      const output = createPlainBytes(state.emittedBytes, length);
      state.emittedBytes += output.byteLength;
      if (truncateAt != null && state.emittedBytes >= truncateAt)
        finished = true;
      return output;
    }

    const output = new Uint8Array(target);
    let written = 0;
    while (written < output.byteLength && !finished) {
      await ensureFramedSegment();
      if (!pending) break;
      const available = pending.byteLength - pendingOffset;
      const take = Math.min(available, output.byteLength - written);
      output.set(
        pending.subarray(pendingOffset, pendingOffset + take),
        written,
      );
      pendingOffset += take;
      written += take;
    }
    if (written === 0) return null;
    let chunk = output.subarray(0, written);
    if (truncateAt != null) {
      const allowed = truncateAt - state.emittedBytes;
      if (allowed <= 0) return null;
      if (chunk.byteLength > allowed) chunk = chunk.subarray(0, allowed);
    }
    state.emittedBytes += chunk.byteLength;
    if (truncateAt != null && state.emittedBytes >= truncateAt) finished = true;
    return chunk;
  }

  const stream = new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const chunk = await nextNetworkChunk();
        if (!chunk) {
          controller.close();
          return;
        }
        controller.enqueue(chunk);
      } catch (error) {
        controller.error(error);
      }
    },
    cancel(reason) {
      state.cancelled = true;
      state.cancelReason =
        reason instanceof Error ? reason.message : String(reason);
    },
  });
  return {
    response: new Response(stream, {
      status: 200,
      headers: {
        "content-length": String(
          options.format === "NONE" ? profile.sizeBytes : profile.encodedSize,
        ),
        "content-type": "application/octet-stream",
      },
    }),
    state,
  };
}
