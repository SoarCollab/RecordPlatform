import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";

import type {
  ChunkManifestEncryption,
  FileDownloadEncryptionVO,
  FileDownloadPartVO,
} from "$api/types";
import type { DownloadSink } from "./downloadSink";
import { assertSha256, createSha256, decodeBase64 } from "./downloadIntegrity";
import type { DownloadMetricsTracker } from "./downloadMetrics";
import {
  assertContentLength,
  DownloadStreamReader,
  requireResponseReader,
} from "./downloadStreamReader";

export const FRAMED_AEAD_SUITE = "RP-AES256-GCM-FRAMED-V2";
export const FRAMED_AEAD_AAD_SCHEMA = "cn.flying.framed-aead.aad.v2";
export const FRAMED_AEAD_FORMAT_VERSION = 2;
export const FRAMED_AEAD_HEADER_BYTES = 44;
export const FRAMED_AEAD_FRAME_HEADER_BYTES = 12;
export const FRAMED_AEAD_TAG_BYTES = 16;
export const MIN_FRAME_PLAIN_BYTES = 64 * 1024;
export const MAX_FRAME_PLAIN_BYTES = 4 * 1024 * 1024;
export const MAX_FRAMES_PER_PART = 10_000;

const MAGIC = new Uint8Array([0x52, 0x50, 0x46, 0x32]); // RPF2
const ALGORITHM_ID_AES_256_GCM = 1;
const KEY_INFO_PREFIX = new TextEncoder().encode(
  "cn.flying.framed-aead.v2/key",
);
const NONCE_INFO_PREFIX = new TextEncoder().encode(
  "cn.flying.framed-aead.v2/nonce",
);
const AAD_PREFIX = new TextEncoder().encode(FRAMED_AEAD_AAD_SCHEMA);

interface FramedChunkHeader {
  chunkIndex: number;
  chunkCount: number;
  framePlainSize: number;
  frameCount: number;
  chunkPlainSize: number;
  fileNonce: Uint8Array;
}

/** 将 typed array 显式标注为 WebCrypto 的 BufferSource。 */
function asBufferSource(bytes: Uint8Array): BufferSource {
  return bytes as unknown as BufferSource;
}

/** 读取无符号大端 32 位整数。 */
function readUint32(bytes: Uint8Array, offset: number): number {
  return new DataView(
    bytes.buffer,
    bytes.byteOffset,
    bytes.byteLength,
  ).getUint32(offset, false);
}

/** 写入无符号大端 32 位整数。 */
function writeUint32(bytes: Uint8Array, offset: number, value: number): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > 0xffffffff) {
    throw new Error("framed AEAD 坐标超出 uint32 范围");
  }
  new DataView(bytes.buffer).setUint32(offset, value, false);
}

/** 拼接固定域与 frame 坐标，构造 HKDF info。 */
function buildFrameInfo(
  prefix: Uint8Array,
  chunkIndex: number,
  frameIndex: number,
): Uint8Array {
  const info = new Uint8Array(prefix.byteLength + 8);
  info.set(prefix, 0);
  writeUint32(info, prefix.byteLength, chunkIndex);
  writeUint32(info, prefix.byteLength + 4, frameIndex);
  return info;
}

/** 构造 Java/TypeScript 共享的规范 AAD 字节。 */
export function buildFramedAad(params: {
  fileNonce: Uint8Array;
  chunkIndex: number;
  chunkCount: number;
  frameIndex: number;
  frameCount: number;
  plainLength: number;
  chunkPlainSize: number;
}): Uint8Array {
  if (params.fileNonce.byteLength !== 16) {
    throw new Error("framed AEAD fileNonce 必须为 16 字节");
  }
  const aad = new Uint8Array(AAD_PREFIX.byteLength + 2 + 16 + 24);
  let offset = 0;
  aad.set(AAD_PREFIX, offset);
  offset += AAD_PREFIX.byteLength;
  aad[offset++] = FRAMED_AEAD_FORMAT_VERSION;
  aad[offset++] = ALGORITHM_ID_AES_256_GCM;
  aad.set(params.fileNonce, offset);
  offset += 16;
  writeUint32(aad, offset, params.chunkIndex);
  writeUint32(aad, offset + 4, params.chunkCount);
  writeUint32(aad, offset + 8, params.frameIndex);
  writeUint32(aad, offset + 12, params.frameCount);
  writeUint32(aad, offset + 16, params.plainLength);
  writeUint32(aad, offset + 20, params.chunkPlainSize);
  return aad;
}

/** 从 file DEK 与 file nonce 派生单帧 key 和 nonce。 */
export function deriveFramedKeyMaterial(params: {
  fileDek: Uint8Array;
  fileNonce: Uint8Array;
  chunkIndex: number;
  frameIndex: number;
}): { key: Uint8Array; nonce: Uint8Array } {
  if (params.fileDek.byteLength !== 32) {
    throw new Error("framed AEAD file DEK 必须为 32 字节");
  }
  if (params.fileNonce.byteLength !== 16) {
    throw new Error("framed AEAD fileNonce 必须为 16 字节");
  }
  return {
    key: hkdf(
      sha256,
      params.fileDek,
      params.fileNonce,
      buildFrameInfo(KEY_INFO_PREFIX, params.chunkIndex, params.frameIndex),
      32,
    ),
    nonce: hkdf(
      sha256,
      params.fileDek,
      params.fileNonce,
      buildFrameInfo(NONCE_INFO_PREFIX, params.chunkIndex, params.frameIndex),
      12,
    ),
  };
}

/** 校验 metadata descriptor 只使用当前实现支持的 v2 套件。 */
export function validateFramedEncryption(
  encryption: FileDownloadEncryptionVO,
): Uint8Array {
  const framePlainSize = encryption.framePlainSize;
  const fileNonceValue = encryption.fileNonce;
  if (
    encryption.formatVersion !== FRAMED_AEAD_FORMAT_VERSION ||
    encryption.algorithmSuite !== FRAMED_AEAD_SUITE ||
    encryption.keyDerivation !== "HKDF-SHA256" ||
    encryption.nonceDerivation !== "HKDF-SHA256" ||
    encryption.aadSchema !== FRAMED_AEAD_AAD_SCHEMA ||
    encryption.tagSize !== FRAMED_AEAD_TAG_BYTES ||
    typeof framePlainSize !== "number" ||
    !Number.isSafeInteger(framePlainSize) ||
    framePlainSize < MIN_FRAME_PLAIN_BYTES ||
    framePlainSize > MAX_FRAME_PLAIN_BYTES ||
    typeof fileNonceValue !== "string"
  ) {
    throw new Error("不支持的 framed AEAD 下载合同");
  }
  const fileNonce = decodeBase64(fileNonceValue);
  if (fileNonce.byteLength !== 16) {
    throw new Error("framed AEAD fileNonce 必须为 16 字节");
  }
  return fileNonce;
}

/** 解析并校验 44 字节 chunk header。 */
function parseChunkHeader(
  bytes: Uint8Array,
  expected: {
    part: FileDownloadPartVO;
    partCount: number;
    encryption: ChunkManifestEncryption;
    fileNonce: Uint8Array;
  },
): FramedChunkHeader {
  for (let index = 0; index < MAGIC.byteLength; index++) {
    if (bytes[index] !== MAGIC[index]) {
      throw new Error("framed AEAD chunk magic 无效");
    }
  }
  if (
    bytes[4] !== FRAMED_AEAD_FORMAT_VERSION ||
    bytes[5] !== ALGORITHM_ID_AES_256_GCM ||
    bytes[6] !== 0 ||
    bytes[7] !== 0
  ) {
    throw new Error("framed AEAD chunk 版本或算法无效");
  }

  const header: FramedChunkHeader = {
    chunkIndex: readUint32(bytes, 8),
    chunkCount: readUint32(bytes, 12),
    framePlainSize: readUint32(bytes, 16),
    frameCount: readUint32(bytes, 20),
    chunkPlainSize: readUint32(bytes, 24),
    fileNonce: bytes.slice(28, 44),
  };
  const expectedFrameCount = Math.ceil(
    header.chunkPlainSize / header.framePlainSize,
  );
  if (
    header.chunkIndex !== expected.part.index ||
    header.chunkCount !== expected.partCount ||
    header.framePlainSize !== expected.encryption.framePlainSize ||
    header.frameCount !== expected.part.frameCount ||
    header.chunkPlainSize !== expected.part.plainSize ||
    header.frameCount !== expectedFrameCount ||
    header.chunkPlainSize <= 0 ||
    header.frameCount <= 0 ||
    header.frameCount > MAX_FRAMES_PER_PART ||
    !header.fileNonce.every((byte, index) => byte === expected.fileNonce[index])
  ) {
    throw new Error("framed AEAD chunk header 与 manifest 不一致");
  }
  return header;
}

/**
 * 将 grant-v1 返回的 DEK 导入为不可提取 HKDF 基础密钥并清理原始字节。
 */
export async function importFramedFileDek(
  fileDekBase64: string,
): Promise<CryptoKey> {
  const fileDek = decodeBase64(fileDekBase64);
  try {
    if (fileDek.byteLength !== 32) {
      throw new Error("framed AEAD file DEK 必须为 32 字节");
    }
    return await globalThis.crypto.subtle.importKey(
      "raw",
      asBufferSource(fileDek),
      "HKDF",
      false,
      ["deriveKey", "deriveBits"],
    );
  } finally {
    fileDek.fill(0);
  }
}

/** 使用不可提取 HKDF 基础密钥派生单帧 AES key 与 nonce。 */
async function deriveFramedCryptoKeyMaterial(params: {
  fileDek: CryptoKey;
  fileNonce: Uint8Array;
  chunkIndex: number;
  frameIndex: number;
}): Promise<{ key: CryptoKey; nonce: Uint8Array }> {
  const keyInfo = buildFrameInfo(
    KEY_INFO_PREFIX,
    params.chunkIndex,
    params.frameIndex,
  );
  const nonceInfo = buildFrameInfo(
    NONCE_INFO_PREFIX,
    params.chunkIndex,
    params.frameIndex,
  );
  try {
    const key = await globalThis.crypto.subtle.deriveKey(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: asBufferSource(params.fileNonce),
        info: asBufferSource(keyInfo),
      },
      params.fileDek,
      { name: "AES-GCM", length: 256 },
      false,
      ["decrypt"],
    );
    const nonceBits = await globalThis.crypto.subtle.deriveBits(
      {
        name: "HKDF",
        hash: "SHA-256",
        salt: asBufferSource(params.fileNonce),
        info: asBufferSource(nonceInfo),
      },
      params.fileDek,
      96,
    );
    return { key, nonce: new Uint8Array(nonceBits) };
  } finally {
    keyInfo.fill(0);
    nonceInfo.fill(0);
  }
}

/** 使用 WebCrypto 验证并解密单个 AES-256-GCM frame。 */
async function decryptFrame(params: {
  ciphertext: Uint8Array;
  fileDek: CryptoKey;
  header: FramedChunkHeader;
  frameIndex: number;
  plainLength: number;
}): Promise<Uint8Array> {
  const material = await deriveFramedCryptoKeyMaterial({
    fileDek: params.fileDek,
    fileNonce: params.header.fileNonce,
    chunkIndex: params.header.chunkIndex,
    frameIndex: params.frameIndex,
  });
  const aad = buildFramedAad({
    fileNonce: params.header.fileNonce,
    chunkIndex: params.header.chunkIndex,
    chunkCount: params.header.chunkCount,
    frameIndex: params.frameIndex,
    frameCount: params.header.frameCount,
    plainLength: params.plainLength,
    chunkPlainSize: params.header.chunkPlainSize,
  });
  try {
    const plaintext = await globalThis.crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: asBufferSource(material.nonce),
        additionalData: asBufferSource(aad),
        tagLength: FRAMED_AEAD_TAG_BYTES * 8,
      },
      material.key,
      asBufferSource(params.ciphertext),
    );
    return new Uint8Array(plaintext);
  } catch {
    throw new Error(
      `framed AEAD 分片 ${params.header.chunkIndex} frame ${params.frameIndex} 认证失败`,
    );
  } finally {
    material.nonce.fill(0);
  }
}

/** 下载、认证并写入一个 framed AEAD part。 */
export async function downloadFramedPart(params: {
  response: Response;
  part: FileDownloadPartVO;
  partCount: number;
  encryption: ChunkManifestEncryption;
  fileDek: CryptoKey;
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
  signal?: AbortSignal;
}): Promise<void> {
  if (
    params.part.plainSize == null ||
    params.part.frameCount == null ||
    !Number.isSafeInteger(params.part.size) ||
    params.part.size <= 0
  ) {
    throw new Error("framed AEAD part 缺少明文尺寸或 frame 数量");
  }
  assertContentLength(params.response, params.part.size);
  const fileNonce = validateFramedEncryption(params.encryption);
  if (
    params.fileDek.type !== "secret" ||
    params.fileDek.extractable ||
    params.fileDek.algorithm.name !== "HKDF"
  ) {
    throw new Error("framed AEAD file DEK 必须为不可提取 HKDF 密钥");
  }

  const cipherHash = createSha256();
  const plainHash = createSha256();
  const stream = new DownloadStreamReader(
    requireResponseReader(params.response),
    params.metrics,
    cipherHash,
  );
  const throwIfAborted = (): void => {
    if (params.signal?.aborted) {
      throw new Error("Download cancelled");
    }
  };
  const onAbort = (): void => {
    void stream.cancel("Download cancelled");
  };
  params.signal?.addEventListener("abort", onAbort, { once: true });
  let encodedLength = 0;
  try {
    throwIfAborted();
    const headerBytes = await stream.readExact(
      FRAMED_AEAD_HEADER_BYTES,
      "framed AEAD chunk header",
    );
    let header: FramedChunkHeader;
    try {
      encodedLength += headerBytes.byteLength;
      header = parseChunkHeader(headerBytes, {
        part: params.part,
        partCount: params.partCount,
        encryption: params.encryption,
        fileNonce,
      });
    } finally {
      stream.release(headerBytes);
    }

    let plainLengthTotal = 0;
    for (let frameIndex = 0; frameIndex < header.frameCount; frameIndex++) {
      throwIfAborted();
      const frameHeader = await stream.readExact(
        FRAMED_AEAD_FRAME_HEADER_BYTES,
        "framed AEAD frame header",
      );
      let declaredFrameIndex: number;
      let plainLength: number;
      let cipherLength: number;
      try {
        encodedLength += frameHeader.byteLength;
        declaredFrameIndex = readUint32(frameHeader, 0);
        plainLength = readUint32(frameHeader, 4);
        cipherLength = readUint32(frameHeader, 8);
      } finally {
        stream.release(frameHeader);
      }

      const remainingPlain = header.chunkPlainSize - plainLengthTotal;
      const expectedPlainLength = Math.min(
        header.framePlainSize,
        remainingPlain,
      );
      if (
        declaredFrameIndex !== frameIndex ||
        plainLength !== expectedPlainLength ||
        cipherLength !== plainLength + FRAMED_AEAD_TAG_BYTES ||
        cipherLength > MAX_FRAME_PLAIN_BYTES + FRAMED_AEAD_TAG_BYTES
      ) {
        throw new Error("framed AEAD frame header 与 manifest 不一致");
      }

      const ciphertext = await stream.readExact(
        cipherLength,
        "framed AEAD frame ciphertext",
      );
      encodedLength += ciphertext.byteLength;
      try {
        throwIfAborted();
        const plaintext = await decryptFrame({
          ciphertext,
          fileDek: params.fileDek,
          header,
          frameIndex,
          plainLength,
        });
        params.metrics.acquire(plaintext.byteLength);
        try {
          if (plaintext.byteLength !== plainLength) {
            throw new Error("framed AEAD frame 明文长度不一致");
          }
          throwIfAborted();
          plainHash.update(plaintext);
          params.metrics.authenticatedFrame();
          await params.sink.write(plaintext);
          params.metrics.wrote(plaintext.byteLength);
          plainLengthTotal += plaintext.byteLength;
        } finally {
          params.metrics.release(plaintext.byteLength);
        }
      } finally {
        stream.release(ciphertext);
      }
    }

    await stream.assertEof();
    if (
      plainLengthTotal !== header.chunkPlainSize ||
      encodedLength !== params.part.size
    ) {
      throw new Error("framed AEAD part 长度校验失败");
    }
    assertSha256(cipherHash.digest(), params.part.cipherHash, "密文分片");
    assertSha256(plainHash.digest(), params.part.plainHash, "明文分片");
    params.metrics.completedPart();
  } catch (error) {
    await stream.cancel(error);
    throw error;
  } finally {
    params.signal?.removeEventListener("abort", onAbort);
  }
}
