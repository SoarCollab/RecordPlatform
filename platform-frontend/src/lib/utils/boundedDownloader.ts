import type {
  ChunkManifestEncryption,
  FileDownloadMetadataVO,
  FileDownloadPartVO,
} from "$api/types";
import { decryptChunk } from "./crypto";
import {
  downloadFramedPart,
  importFramedFileDek,
  MAX_FRAMES_PER_PART,
  validateFramedEncryption,
} from "./framedAead";
import { assertSha256, createSha256 } from "./downloadIntegrity";
import {
  DownloadMetricsTracker,
  type DownloadStreamMetrics,
} from "./downloadMetrics";
import type { DownloadSink } from "./downloadSink";
import {
  assertContentLength,
  requireResponseReader,
} from "./downloadStreamReader";

export const LEGACY_MAX_CIPHER_PART_BYTES = 80 * 1024 * 1024 + 4 * 1024;
export const MAX_PARTS_PER_FILE = 10_000;
const MAX_NETWORK_CHUNK_BYTES = 1 * 1024 * 1024;
const MAX_FETCH_ATTEMPTS = 3;
const LEGACY_ENCRYPTION_ALGORITHMS = new Set([
  "AES-GCM",
  "AES-256-GCM",
  "CHACHA20",
  "CHACHA20-POLY1305",
]);
const MANIFEST_SECRET_KEYS = new Set([
  "key",
  "keys",
  "secret",
  "initialkey",
  "filedek",
  "dek",
  "datakey",
  "encrypteddatakey",
  "wrappeddatakey",
  "decryptkey",
  "decryptionkey",
  "encryptionkey",
  "filekey",
  "filedatakey",
  "wrappingiv",
  "kmskeyid",
  "privatekey",
  "secretkey",
]);

export type DownloadFormat = "NONE" | "LEGACY_V1" | "FRAMED_V2";

export interface BoundedDownloadOptions {
  metadata: FileDownloadMetadataVO;
  /** grant-v1 消费后仅在当前执行作用域内存在的瞬时密钥。 */
  initialKey?: string | null;
  /** 调用任务中已确认的文件 hash，用于阻止跨文件 metadata 替换。 */
  expectedFileHash?: string;
  sink: DownloadSink;
  signal?: AbortSignal;
  fetchImpl?: typeof fetch;
  metrics?: DownloadMetricsTracker;
  onPartComplete?: (completed: number, total: number) => void;
  /** 401/403 时重新授权并返回同一身份的新 metadata；最多调用一次。 */
  refreshMetadata?: (
    error: DownloadMetadataExpiredError,
  ) => Promise<FileDownloadMetadataVO>;
}

/** 预签名 URL 已失效；调用方只能在身份栅栏一致时刷新一次 metadata。 */
export class DownloadMetadataExpiredError extends Error {
  constructor(
    readonly status: 401 | 403,
    readonly partIndex: number,
  ) {
    super(`分片 ${partIndex + 1} 下载地址已过期`);
    this.name = "DownloadMetadataExpiredError";
  }
}

/** 无 manifest 历史接口使用的有界下载参数。 */
export interface LegacyFallbackDownloadOptions {
  urls: string[];
  fileSize: number;
  totalChunks: number;
  initialKey?: string | null;
  encrypted: boolean;
  sink: DownloadSink;
  signal?: AbortSignal;
  fetchImpl?: typeof fetch;
  metrics?: DownloadMetricsTracker;
  onPartComplete?: (completed: number, total: number) => void;
}

/** 按 metadata 明确选择 NONE、历史 v1 或 framed v2。 */
export function resolveDownloadFormat(
  metadata: FileDownloadMetadataVO,
): DownloadFormat {
  const algorithm = metadata.encryptionAlgorithm?.trim().toUpperCase();
  const version = metadata.encryption?.formatVersion;
  if (version === 0) {
    if (algorithm != null && algorithm !== "NONE") {
      throw new Error("下载加密算法与 formatVersion 冲突");
    }
    return "NONE";
  }
  if (algorithm === "NONE") {
    if (version != null) {
      throw new Error("下载加密算法与 formatVersion 冲突");
    }
    return "NONE";
  }
  if (version === 2) {
    if (algorithm !== "FRAMED_AEAD_V2") {
      throw new Error("下载加密算法与 formatVersion 冲突");
    }
    return "FRAMED_V2";
  }
  if (version == null || version === 1) {
    if (algorithm == null || LEGACY_ENCRYPTION_ALGORITHMS.has(algorithm)) {
      return "LEGACY_V1";
    }
    if (algorithm === "FRAMED_AEAD_V2") {
      throw new Error("下载加密算法与 formatVersion 冲突");
    }
    throw new Error(`不支持的下载加密算法: ${algorithm}`);
  }
  throw new Error(`不支持的下载格式版本: ${version}`);
}

/** 校验 parts 有序、连续且数量与 metadata 一致。 */
function validateParts(metadata: FileDownloadMetadataVO): FileDownloadPartVO[] {
  if (
    !Number.isSafeInteger(metadata.fileSize) ||
    metadata.fileSize < 0 ||
    !Number.isSafeInteger(metadata.totalChunks) ||
    metadata.totalChunks <= 0 ||
    metadata.totalChunks > MAX_PARTS_PER_FILE ||
    metadata.parts.length !== metadata.totalChunks
  ) {
    throw new Error("下载 metadata 的文件或分片数量无效");
  }
  // metadata.parts 已是后端承诺的有序视图；排序会把重排攻击静默恢复成“合法”顺序。
  const parts = [...metadata.parts];
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    if (
      part.index !== index ||
      !Number.isSafeInteger(part.size) ||
      part.size <= 0 ||
      !part.downloadUrl
    ) {
      throw new Error("下载 metadata 的分片顺序或大小无效");
    }
    if (
      part.plainSize != null &&
      (!Number.isSafeInteger(part.plainSize) || part.plainSize <= 0)
    ) {
      throw new Error("下载 metadata 的明文分片大小无效");
    }
    if (
      part.frameCount != null &&
      (!Number.isSafeInteger(part.frameCount) ||
        part.frameCount <= 0 ||
        part.frameCount > MAX_FRAMES_PER_PART)
    ) {
      throw new Error("下载 metadata 的 frame 数量无效");
    }
    if (
      part.storageBackend != null &&
      typeof part.storageBackend !== "string"
    ) {
      throw new Error("下载 metadata 的存储后端无效");
    }
    if (part.etag != null && typeof part.etag !== "string") {
      throw new Error("下载 metadata 的 ETag 无效");
    }
  }
  return parts;
}

type ManifestRecord = Record<string, unknown>;

/** 将未知 JSON 值收窄为普通对象。 */
function asManifestRecord(value: unknown, label: string): ManifestRecord {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} 不是有效对象`);
  }
  return value as ManifestRecord;
}

/** 校验 canonical manifest 中的必需字符串字段。 */
function requireManifestString(
  record: ManifestRecord,
  key: string,
  expected: string,
): void {
  if (record[key] !== expected) {
    throw new Error(`下载 manifest 字段 ${key} 与 metadata 不一致`);
  }
}

/** 校验 canonical manifest 中的安全整数字段。 */
function requireManifestInteger(
  record: ManifestRecord,
  key: string,
  expected: number,
): void {
  if (!Number.isSafeInteger(record[key]) || record[key] !== expected) {
    throw new Error(`下载 manifest 字段 ${key} 与 metadata 不一致`);
  }
}

/** 校验可选字段在 canonical JSON 中按 NON_NULL 规则出现。 */
function requireOptionalManifestValue(
  record: ManifestRecord,
  key: string,
  expected: string | number | null | undefined,
): void {
  const hasValue = Object.prototype.hasOwnProperty.call(record, key);
  if (expected == null) {
    if (hasValue) {
      throw new Error(`下载 manifest 字段 ${key} 不应存在`);
    }
    return;
  }
  if (!hasValue || record[key] !== expected) {
    throw new Error(`下载 manifest 字段 ${key} 与 metadata 不一致`);
  }
}

/** 拒绝 canonical manifest 中意外出现的密钥材料字段。 */
function rejectManifestSecrets(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(rejectManifestSecrets);
    return;
  }
  if (value === null || typeof value !== "object") return;
  for (const [key, nested] of Object.entries(value)) {
    const normalizedKey = key.toLowerCase().replace(/[^a-z0-9]/g, "");
    if (MANIFEST_SECRET_KEYS.has(normalizedKey)) {
      throw new Error("canonical manifest 不得包含密钥材料");
    }
    rejectManifestSecrets(nested);
  }
}

/** 在提交下载输出前重新检查取消状态，避免取消后仍 close 成功。 */
function throwIfDownloadCancelled(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw new Error("Download cancelled");
  }
}

/**
 * 在请求对象 URL 前校验 canonical manifest 的 hash、文件绑定和分片合同。
 * canonical JSON 由后端 canonicalizer 生成，前端只接受与 metadata 完全一致的视图。
 */
function validateCanonicalManifest(
  metadata: FileDownloadMetadataVO,
  parts: FileDownloadPartVO[],
  format: DownloadFormat,
  expectedFileHash?: string,
): void {
  if (!metadata.canonicalManifestJson?.trim()) {
    throw new Error("下载 metadata 缺少 canonical manifest JSON");
  }
  if (
    !metadata.fileHash ||
    (expectedFileHash && metadata.fileHash !== expectedFileHash)
  ) {
    throw new Error("下载 metadata 的 fileHash 与任务不一致");
  }
  const manifestBytes = new TextEncoder().encode(
    metadata.canonicalManifestJson,
  );
  const manifestDigest = createSha256();
  manifestDigest.update(manifestBytes);
  assertSha256(manifestDigest.digest(), metadata.manifestHash, "manifest");

  let parsed: unknown;
  try {
    parsed = JSON.parse(metadata.canonicalManifestJson);
  } catch {
    throw new Error("下载 metadata 的 canonical manifest JSON 无效");
  }
  rejectManifestSecrets(parsed);
  const root = asManifestRecord(parsed, "canonical manifest");
  requireManifestString(root, "schema", metadata.manifestSchemaId);
  requireManifestString(root, "fileHash", metadata.fileHash);
  requireManifestString(root, "hashAlgorithm", metadata.hashAlgorithm);
  requireManifestInteger(root, "chunkSize", metadata.chunkSize);
  if (metadata.encryptionAlgorithm == null) {
    requireOptionalManifestValue(root, "encryptionAlgorithm", null);
  } else {
    requireManifestString(
      root,
      "encryptionAlgorithm",
      metadata.encryptionAlgorithm,
    );
  }
  requireManifestString(root, "storageBackend", metadata.storageBackend);

  const expectedTotalSize = parts.reduce((sum, part) => {
    const value = format === "FRAMED_V2" ? (part.plainSize ?? -1) : part.size;
    if (
      !Number.isSafeInteger(value) ||
      value <= 0 ||
      !Number.isSafeInteger(sum + value)
    ) {
      throw new Error("下载 manifest 分片总量无效");
    }
    return sum + value;
  }, 0);
  const rootTotalSize = root.totalSize;
  if (
    !Number.isSafeInteger(rootTotalSize) ||
    rootTotalSize !== expectedTotalSize
  ) {
    throw new Error("下载 manifest 总大小与 metadata 不一致");
  }
  if (format !== "LEGACY_V1" && rootTotalSize !== metadata.fileSize) {
    throw new Error("下载 manifest 明文总量与文件大小不一致");
  }

  const manifestChunks = root.chunks;
  if (
    !Array.isArray(manifestChunks) ||
    manifestChunks.length !== parts.length
  ) {
    throw new Error("下载 manifest 分片数量与 metadata 不一致");
  }
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    const chunk = asManifestRecord(
      manifestChunks[index],
      `canonical manifest chunk ${index}`,
    );
    requireManifestInteger(chunk, "index", index);
    requireManifestString(chunk, "plainHash", part.plainHash);
    requireManifestString(chunk, "cipherHash", part.cipherHash);
    requireManifestInteger(chunk, "size", part.size);
    requireManifestString(chunk, "storagePath", part.storagePath);
    requireManifestString(
      chunk,
      "storageBackend",
      part.storageBackend ?? metadata.storageBackend,
    );
    requireManifestString(
      chunk,
      "checksumAlgorithm",
      part.checksumAlgorithm ?? "SHA-256",
    );
    requireOptionalManifestValue(chunk, "etag", part.etag);
    requireOptionalManifestValue(chunk, "plainSize", part.plainSize);
    requireOptionalManifestValue(chunk, "frameCount", part.frameCount);
  }

  const manifestEncryption = Object.prototype.hasOwnProperty.call(
    root,
    "encryption",
  )
    ? asManifestRecord(root.encryption, "canonical manifest encryption")
    : null;
  if (metadata.encryption == null) {
    if (manifestEncryption !== null) {
      throw new Error("下载 manifest encryption 与 metadata 不一致");
    }
  } else {
    if (manifestEncryption === null) {
      throw new Error("下载 manifest 缺少 encryption 描述");
    }
    for (const [key, value] of Object.entries(metadata.encryption)) {
      requireOptionalManifestValue(
        manifestEncryption,
        key,
        value as string | number | null | undefined,
      );
    }
  }
}

/** 对预签名 URL 执行有限网络重试，401/403 直接要求刷新 metadata。 */
async function fetchDownloadUrl(
  url: string,
  index: number,
  signal: AbortSignal | undefined,
  fetchImpl: typeof fetch,
): Promise<Response> {
  /** 释放失败响应体，避免重试时遗留未消费的网络流。 */
  const cancelErrorResponse = async (response: Response): Promise<void> => {
    if (!response.body) return;
    try {
      await response.body.cancel("download request failed");
    } catch {
      // 响应体可能已由浏览器关闭，不能覆盖原始下载错误。
    }
  };

  let lastError: Error | null = null;
  for (let attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
    if (signal?.aborted) throw new Error("Download cancelled");

    let response: Response;
    try {
      response = await fetchImpl(url, { signal });
    } catch (error) {
      if (signal?.aborted) {
        throw new Error("Download cancelled", { cause: error });
      }
      lastError =
        error instanceof Error ? error : new Error(String(error ?? "网络错误"));
      if (attempt === MAX_FETCH_ATTEMPTS) {
        throw lastError;
      }
      await new Promise((resolve) =>
        setTimeout(resolve, 200 * 2 ** (attempt - 1)),
      );
      continue;
    }

    if (response.status === 401 || response.status === 403) {
      await cancelErrorResponse(response);
      throw new DownloadMetadataExpiredError(response.status, index);
    }
    if (response.ok) return response;

    const error = new Error(`分片 ${index + 1} 下载失败: ${response.status}`);
    // 只有 5xx 才属于可重试的 HTTP 失败；4xx 立即失败，避免无效重试。
    if (response.status < 500 || attempt === MAX_FETCH_ATTEMPTS) {
      await cancelErrorResponse(response);
      throw error;
    }
    await cancelErrorResponse(response);
    lastError = error;
    await new Promise((resolve) =>
      setTimeout(resolve, 200 * 2 ** (attempt - 1)),
    );
  }
  throw lastError ?? new Error("分片下载失败");
}

/** 比较刷新前后的可选字段，避免 `undefined`/`null` 漂移被忽略。 */
function requireRefreshFieldEqual(
  current: unknown,
  refreshed: unknown,
  field: string,
): void {
  if (current !== refreshed) {
    throw new Error(`下载 metadata 刷新后 ${field} 发生漂移`);
  }
}

/**
 * 校验刷新只替换 downloadUrl、expiresAt 和一次性 key grant，不得改变任何文件或 manifest 身份。
 */
function validateRefreshedMetadata(
  current: FileDownloadMetadataVO,
  refreshed: FileDownloadMetadataVO,
  expectedFileHash?: string,
): FileDownloadPartVO[] {
  const refreshedParts = validateParts(refreshed);
  const refreshedFormat = resolveDownloadFormat(refreshed);
  validateCanonicalManifest(
    refreshed,
    refreshedParts,
    refreshedFormat,
    expectedFileHash,
  );

  const stableTopLevelFields: Array<keyof FileDownloadMetadataVO> = [
    "fileId",
    "fileHash",
    "fileName",
    "fileSize",
    "contentType",
    "manifestSchemaId",
    "manifestHash",
    "canonicalManifestJson",
    "manifestStatus",
    "manifestClassification",
    "manifestErrorCode",
    "legacyDownloadAllowed",
    "hashAlgorithm",
    "encryptionAlgorithm",
    "storageBackend",
    "chunkSize",
    "totalChunks",
  ];
  for (const field of stableTopLevelFields) {
    requireRefreshFieldEqual(current[field], refreshed[field], field);
  }
  if (
    JSON.stringify(current.encryption ?? null) !==
    JSON.stringify(refreshed.encryption ?? null)
  ) {
    throw new Error("下载 metadata 刷新后 encryption 发生漂移");
  }
  if (
    current.accessIdentity.accessKind !== refreshed.accessIdentity.accessKind ||
    current.accessIdentity.identityHash !==
      refreshed.accessIdentity.identityHash ||
    current.accessIdentity.fileVersion !==
      refreshed.accessIdentity.fileVersion ||
    current.accessIdentity.manifestHash !==
      refreshed.accessIdentity.manifestHash ||
    current.accessIdentity.algorithmSuite !==
      refreshed.accessIdentity.algorithmSuite
  ) {
    throw new Error("下载 metadata 刷新后 accessIdentity 发生漂移");
  }

  for (let index = 0; index < current.parts.length; index++) {
    const before = current.parts[index];
    const after = refreshedParts[index];
    const stablePartFields: Array<keyof FileDownloadPartVO> = [
      "index",
      "size",
      "storagePath",
      "storageBackend",
      "etag",
      "plainHash",
      "cipherHash",
      "checksumAlgorithm",
      "plainSize",
      "frameCount",
    ];
    for (const field of stablePartFields) {
      requireRefreshFieldEqual(
        before[field],
        after[field],
        `parts[${index}].${field}`,
      );
    }
  }
  return refreshedParts;
}

/** 创建只允许一次身份一致 metadata 刷新的分片响应解析器。 */
function createRefreshablePartFetcher(params: {
  metadata: FileDownloadMetadataVO;
  parts: FileDownloadPartVO[];
  expectedFileHash?: string;
  signal?: AbortSignal;
  fetchImpl: typeof fetch;
  refreshMetadata?: (
    error: DownloadMetadataExpiredError,
  ) => Promise<FileDownloadMetadataVO>;
}): {
  fetchPart(index: number): Promise<Response>;
} {
  let currentMetadata = params.metadata;
  let currentParts = params.parts;
  let refreshAttempted = false;

  return {
    async fetchPart(index: number): Promise<Response> {
      try {
        return await fetchPart(
          currentParts[index],
          params.signal,
          params.fetchImpl,
        );
      } catch (error) {
        if (
          !(error instanceof DownloadMetadataExpiredError) ||
          !params.refreshMetadata ||
          refreshAttempted
        ) {
          throw error;
        }
        refreshAttempted = true;
        const refreshed = await params.refreshMetadata(error);
        currentParts = validateRefreshedMetadata(
          currentMetadata,
          refreshed,
          params.expectedFileHash,
        );
        currentMetadata = refreshed;
        return fetchPart(currentParts[index], params.signal, params.fetchImpl);
      }
    },
  };
}

/** 对带 manifest 的分片调用有界 URL 获取逻辑。 */
async function fetchPart(
  part: FileDownloadPartVO,
  signal: AbortSignal | undefined,
  fetchImpl: typeof fetch,
): Promise<Response> {
  return fetchDownloadUrl(part.downloadUrl, part.index, signal, fetchImpl);
}

/** 读取没有 manifest 尺寸证据的历史密文分片，并强制执行硬上限。 */
async function readLegacyCipherResponse(params: {
  response: Response;
  signal?: AbortSignal;
  metrics: DownloadMetricsTracker;
}): Promise<Uint8Array> {
  const rawLength = params.response.headers.get("content-length");
  const declaredLength = rawLength == null ? null : Number(rawLength);
  if (
    declaredLength != null &&
    (!Number.isSafeInteger(declaredLength) ||
      declaredLength < 0 ||
      declaredLength > LEGACY_MAX_CIPHER_PART_BYTES)
  ) {
    throw new Error("历史加密分片超过兼容读取上限");
  }

  const reader = requireResponseReader(params.response);
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  let result: Uint8Array | null = null;
  try {
    while (true) {
      if (params.signal?.aborted) throw new Error("Download cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      if (value.byteLength > MAX_NETWORK_CHUNK_BYTES) {
        throw new Error("网络读取块超过有界下载上限");
      }
      totalBytes += value.byteLength;
      if (totalBytes > LEGACY_MAX_CIPHER_PART_BYTES) {
        throw new Error("历史加密分片超过兼容读取上限");
      }
      if (declaredLength != null && totalBytes > declaredLength) {
        throw new Error("历史加密分片超过响应声明大小");
      }
      params.metrics.acquire(value.byteLength);
      chunks.push(value);
    }
    if (declaredLength != null && totalBytes !== declaredLength) {
      throw new Error("历史加密分片长度与响应声明不一致");
    }
    result = new Uint8Array(totalBytes);
    params.metrics.acquire(result.byteLength);
    let offset = 0;
    for (const chunk of chunks) {
      result.set(chunk, offset);
      offset += chunk.byteLength;
      params.metrics.release(chunk.byteLength);
    }
    chunks.length = 0;
    return result;
  } catch (error) {
    if (result) {
      params.metrics.release(result.byteLength);
    }
    for (const chunk of chunks) {
      params.metrics.release(chunk.byteLength);
    }
    chunks.length = 0;
    try {
      await reader.cancel(error);
    } catch {
      // reader 已结束时忽略取消错误。
    }
    throw error;
  }
}

/** 将无 manifest 的历史明文响应按总文件大小增量写入 sink。 */
async function streamLegacyPlainResponse(params: {
  response: Response;
  remainingBytes: number;
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
  signal?: AbortSignal;
}): Promise<number> {
  const rawLength = params.response.headers.get("content-length");
  const declaredLength = rawLength == null ? null : Number(rawLength);
  if (
    declaredLength != null &&
    (!Number.isSafeInteger(declaredLength) ||
      declaredLength < 0 ||
      declaredLength > params.remainingBytes)
  ) {
    throw new Error("历史明文分片超过文件声明大小");
  }
  const reader = requireResponseReader(params.response);
  let totalBytes = 0;
  try {
    while (true) {
      if (params.signal?.aborted) throw new Error("Download cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      if (value.byteLength > MAX_NETWORK_CHUNK_BYTES) {
        throw new Error("网络读取块超过有界下载上限");
      }
      totalBytes += value.byteLength;
      if (totalBytes > params.remainingBytes) {
        throw new Error("历史明文分片超过文件声明大小");
      }
      params.metrics.acquire(value.byteLength);
      try {
        await params.sink.write(value);
        params.metrics.wrote(value.byteLength);
      } finally {
        params.metrics.release(value.byteLength);
      }
    }
    if (declaredLength != null && totalBytes !== declaredLength) {
      throw new Error("历史明文分片长度与响应声明不一致");
    }
    return totalBytes;
  } catch (error) {
    try {
      await reader.cancel(error);
    } catch {
      // reader 已结束时忽略取消错误。
    }
    throw error;
  }
}

/** 增量下载 NONE part，边读边校验长度/摘要并背压写入 sink。 */
async function downloadPlainPart(params: {
  response: Response;
  part: FileDownloadPartVO;
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
  signal?: AbortSignal;
}): Promise<void> {
  if (
    params.part.plainSize != null &&
    params.part.plainSize !== params.part.size
  ) {
    throw new Error("未加密分片明文尺寸与对象尺寸不一致");
  }
  assertContentLength(params.response, params.part.size);
  const reader = requireResponseReader(params.response);
  const hash = createSha256();
  let totalBytes = 0;
  try {
    while (true) {
      if (params.signal?.aborted) throw new Error("Download cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      if (value.byteLength > MAX_NETWORK_CHUNK_BYTES) {
        throw new Error("网络读取块超过有界下载上限");
      }
      params.metrics.acquire(value.byteLength);
      try {
        totalBytes += value.byteLength;
        if (totalBytes > params.part.size) {
          throw new Error("未加密分片超过 manifest 声明大小");
        }
        hash.update(value);
        await params.sink.write(value);
        params.metrics.wrote(value.byteLength);
      } finally {
        params.metrics.release(value.byteLength);
      }
    }
  } catch (error) {
    try {
      await reader.cancel(error);
    } catch {
      // reader 已关闭时忽略取消错误。
    }
    throw error;
  }
  if (totalBytes !== params.part.size) {
    throw new Error("未加密分片长度与 manifest 不一致");
  }
  const digest = hash.digest();
  assertSha256(digest, params.part.cipherHash, "密文分片");
  assertSha256(digest, params.part.plainHash, "明文分片");
  params.metrics.completedPart();
}

/** 在明确硬上限内读取一个历史 v1 密文 part。 */
async function downloadLegacyCipherPart(params: {
  response: Response;
  part: FileDownloadPartVO;
  metrics: DownloadMetricsTracker;
  signal?: AbortSignal;
}): Promise<Uint8Array> {
  if (params.part.size > LEGACY_MAX_CIPHER_PART_BYTES) {
    throw new Error("历史加密分片超过兼容读取上限");
  }
  assertContentLength(params.response, params.part.size);
  const reader = requireResponseReader(params.response);
  const data = new Uint8Array(params.part.size);
  params.metrics.acquire(data.byteLength);
  const hash = createSha256();
  let offset = 0;
  try {
    while (true) {
      if (params.signal?.aborted) throw new Error("Download cancelled");
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.byteLength === 0) continue;
      if (value.byteLength > MAX_NETWORK_CHUNK_BYTES) {
        throw new Error("网络读取块超过有界下载上限");
      }
      params.metrics.acquire(value.byteLength);
      try {
        const end = offset + value.byteLength;
        if (end > data.byteLength) {
          throw new Error("历史加密分片超过 manifest 声明大小");
        }
        data.set(value, offset);
        hash.update(value);
        offset = end;
      } finally {
        params.metrics.release(value.byteLength);
      }
    }
    if (offset !== data.byteLength) {
      throw new Error("历史加密分片长度与 manifest 不一致");
    }
    assertSha256(hash.digest(), params.part.cipherHash, "历史密文分片");
    return data;
  } catch (error) {
    params.metrics.release(data.byteLength);
    try {
      await reader.cancel(error);
    } catch {
      // reader 已关闭时忽略取消错误。
    }
    throw error;
  }
}

/** 校验 v1 明文摘要并写入原始文件偏移。 */
async function writeLegacyPlaintext(params: {
  plaintext: Uint8Array;
  embeddedHash: string | null;
  part: FileDownloadPartVO;
  position: number;
  expectedPlainSize: number;
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
}): Promise<void> {
  params.metrics.acquire(params.plaintext.byteLength);
  try {
    if (params.plaintext.byteLength !== params.expectedPlainSize) {
      throw new Error("历史加密分片明文长度不一致");
    }
    const hash = createSha256();
    hash.update(params.plaintext);
    const digest = hash.digest();
    assertSha256(digest, params.part.plainHash, "历史明文分片");
    if (params.embeddedHash) {
      assertSha256(digest, params.embeddedHash, "历史内嵌明文分片");
    }
    await params.sink.writeAt(params.position, params.plaintext);
    params.metrics.wrote(params.plaintext.byteLength);
    params.metrics.completedPart();
  } finally {
    params.metrics.release(params.plaintext.byteLength);
  }
}

/** 计算历史 v1 part 的明文偏移和声明长度。 */
function resolveLegacyPlainRange(
  metadata: FileDownloadMetadataVO,
  part: FileDownloadPartVO,
): { position: number; size: number } {
  const position = part.index * metadata.chunkSize;
  const inferredSize = Math.min(
    metadata.chunkSize,
    metadata.fileSize - position,
  );
  const size = part.plainSize ?? inferredSize;
  const end = position + size;
  if (
    !Number.isSafeInteger(position) ||
    position < 0 ||
    !Number.isSafeInteger(size) ||
    size <= 0 ||
    !Number.isSafeInteger(end) ||
    end > metadata.fileSize
  ) {
    throw new Error("历史加密分片明文范围无效");
  }
  return { position, size };
}

/**
 * 校验历史 v1 分片的明文范围连续覆盖整个文件，拒绝 gap、overlap 和尺寸漂移。
 *
 * v1 需要按固定分片偏移随机写入；FileSystem sink 不会替调用方检查稀疏区间，
 * 因此必须在发起网络请求前确认每个可选 plainSize 都与上传计划一致。
 */
function validateLegacyPlainRanges(
  metadata: FileDownloadMetadataVO,
  parts: FileDownloadPartVO[],
): Array<{ position: number; size: number }> {
  if (
    !Number.isSafeInteger(metadata.chunkSize) ||
    metadata.chunkSize <= 0 ||
    !Number.isSafeInteger(metadata.fileSize) ||
    metadata.fileSize <= 0
  ) {
    throw new Error("历史加密文件范围合同无效");
  }

  const ranges: Array<{ position: number; size: number }> = [];
  let nextPosition = 0;
  for (const part of parts) {
    const range = resolveLegacyPlainRange(metadata, part);
    const expectedSize = Math.min(
      metadata.chunkSize,
      metadata.fileSize - range.position,
    );
    if (
      range.position !== nextPosition ||
      range.size !== expectedSize ||
      !Number.isSafeInteger(nextPosition + range.size)
    ) {
      throw new Error("历史加密分片明文范围不连续或尺寸不一致");
    }
    nextPosition += range.size;
    ranges.push(range);
  }

  if (nextPosition !== metadata.fileSize) {
    throw new Error("历史加密分片明文范围未覆盖完整文件");
  }
  return ranges;
}

/** 执行历史 v1 环形密钥链兼容下载。 */
async function downloadLegacyFile(params: {
  metadata: FileDownloadMetadataVO;
  initialKey: string;
  parts: FileDownloadPartVO[];
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
  signal?: AbortSignal;
  fetchPart: (index: number) => Promise<Response>;
  onPartComplete?: (completed: number, total: number) => void;
}): Promise<void> {
  if (!params.sink.supportsRandomAccess && params.metadata.fileSize > 0) {
    throw new Error("历史加密下载需要支持随机写入的临时文件");
  }
  for (const part of params.parts) {
    if (part.size > LEGACY_MAX_CIPHER_PART_BYTES) {
      throw new Error("历史加密分片超过兼容读取上限");
    }
  }
  // 先验证所有随机写入范围，避免下载后才发现稀疏或重叠输出。
  const plainRanges = validateLegacyPlainRanges(params.metadata, params.parts);

  let completed = 0;
  const lastPart = params.parts.at(-1)!;
  const lastResponse = await params.fetchPart(lastPart.index);
  const lastCipher = await downloadLegacyCipherPart({
    response: lastResponse,
    part: lastPart,
    metrics: params.metrics,
    signal: params.signal,
  });
  let firstKey: string;
  try {
    const result = await decryptChunk(lastCipher, params.initialKey);
    const range = plainRanges.at(-1)!;
    await writeLegacyPlaintext({
      plaintext: result.plaintext,
      embeddedHash: result.hash,
      part: lastPart,
      position: range.position,
      expectedPlainSize: range.size,
      sink: params.sink,
      metrics: params.metrics,
    });
    if (params.parts.length > 1 && !result.nextKey) {
      throw new Error("历史最后分片缺少首分片密钥");
    }
    firstKey = result.nextKey ?? params.initialKey;
  } finally {
    params.metrics.release(lastCipher.byteLength);
  }
  completed++;
  params.onPartComplete?.(completed, params.parts.length);

  let currentKey = firstKey;
  for (let index = 0; index < params.parts.length - 1; index++) {
    const part = params.parts[index];
    const response = await params.fetchPart(part.index);
    const ciphertext = await downloadLegacyCipherPart({
      response,
      part,
      metrics: params.metrics,
      signal: params.signal,
    });
    try {
      const result = await decryptChunk(ciphertext, currentKey);
      const range = plainRanges[index];
      await writeLegacyPlaintext({
        plaintext: result.plaintext,
        embeddedHash: result.hash,
        part,
        position: range.position,
        expectedPlainSize: range.size,
        sink: params.sink,
        metrics: params.metrics,
      });
      if (index < params.parts.length - 2 && !result.nextKey) {
        throw new Error(`历史分片 ${index} 缺少下一分片密钥`);
      }
      currentKey = result.nextKey ?? currentKey;
    } finally {
      params.metrics.release(ciphertext.byteLength);
    }
    completed++;
    params.onPartComplete?.(completed, params.parts.length);
  }
}

/** 校验历史分片内嵌摘要并按固定明文偏移写入 sink。 */
async function writeLegacyPlaintextWithoutManifest(params: {
  plaintext: Uint8Array;
  embeddedHash: string | null;
  position: number;
  sink: DownloadSink;
  metrics: DownloadMetricsTracker;
}): Promise<void> {
  if (!Number.isSafeInteger(params.position) || params.position < 0) {
    throw new Error("历史加密分片写入偏移无效");
  }
  params.metrics.acquire(params.plaintext.byteLength);
  try {
    if (params.embeddedHash) {
      const digest = createSha256();
      digest.update(params.plaintext);
      assertSha256(digest.digest(), params.embeddedHash, "历史内嵌明文分片");
    }
    await params.sink.writeAt(params.position, params.plaintext);
    params.metrics.wrote(params.plaintext.byteLength);
  } finally {
    params.metrics.release(params.plaintext.byteLength);
  }
}

/** 无 manifest 时按 URL 顺序或 v1 环形密钥链执行有界下载。 */
export async function executeLegacyFallbackDownload(
  options: LegacyFallbackDownloadOptions,
): Promise<DownloadStreamMetrics> {
  const metrics = options.metrics ?? new DownloadMetricsTracker();
  const fetchImpl = options.fetchImpl ?? fetch;

  try {
    if (
      !Number.isSafeInteger(options.fileSize) ||
      options.fileSize < 0 ||
      !Number.isSafeInteger(options.totalChunks) ||
      options.totalChunks < 0 ||
      options.totalChunks > 10_000 ||
      options.urls.length !== options.totalChunks
    ) {
      throw new Error("历史下载参数中的文件大小或分片数量无效");
    }
    if (options.totalChunks === 0 && options.fileSize !== 0) {
      throw new Error("历史下载缺少分片 URL");
    }
    if (options.totalChunks === 0) {
      throwIfDownloadCancelled(options.signal);
      await options.sink.close();
      return metrics.snapshot();
    }
    if (!options.encrypted) {
      let written = 0;
      for (let index = 0; index < options.urls.length; index++) {
        const response = await fetchDownloadUrl(
          options.urls[index],
          index,
          options.signal,
          fetchImpl,
        );
        const partBytes = await streamLegacyPlainResponse({
          response,
          remainingBytes: options.fileSize - written,
          sink: options.sink,
          metrics,
          signal: options.signal,
        });
        if (partBytes === 0 && options.fileSize > 0) {
          throw new Error("历史明文分片为空");
        }
        written += partBytes;
        metrics.completedPart();
        options.onPartComplete?.(index + 1, options.totalChunks);
      }
      if (written !== options.fileSize) {
        throw new Error(
          `历史明文总长度不一致: 期望 ${options.fileSize}，实际 ${written}`,
        );
      }
    } else {
      if (!options.initialKey) {
        throw new Error("历史加密文件缺少 initialKey");
      }
      if (!options.sink.supportsRandomAccess && options.fileSize > 0) {
        throw new Error("历史加密下载需要支持随机写入的临时文件");
      }

      const lastIndex = options.totalChunks - 1;
      const lastResponse = await fetchDownloadUrl(
        options.urls[lastIndex],
        lastIndex,
        options.signal,
        fetchImpl,
      );
      const lastCipher = await readLegacyCipherResponse({
        response: lastResponse,
        signal: options.signal,
        metrics,
      });
      let lastPlainLength = 0;
      let currentKey = options.initialKey;
      try {
        const result = await decryptChunk(lastCipher, options.initialKey);
        lastPlainLength = result.plaintext.byteLength;
        const lastPosition = options.fileSize - lastPlainLength;
        if (lastPosition < 0) {
          throw new Error("历史最后分片明文超过文件声明大小");
        }
        await writeLegacyPlaintextWithoutManifest({
          plaintext: result.plaintext,
          embeddedHash: result.hash,
          position: lastPosition,
          sink: options.sink,
          metrics,
        });
        if (options.totalChunks > 1 && !result.nextKey) {
          throw new Error("历史最后分片缺少首分片密钥");
        }
        currentKey = result.nextKey ?? currentKey;
      } finally {
        metrics.release(lastCipher.byteLength);
      }
      metrics.completedPart();
      options.onPartComplete?.(1, options.totalChunks);

      let position = 0;
      for (let index = 0; index < lastIndex; index++) {
        const response = await fetchDownloadUrl(
          options.urls[index],
          index,
          options.signal,
          fetchImpl,
        );
        const ciphertext = await readLegacyCipherResponse({
          response,
          signal: options.signal,
          metrics,
        });
        try {
          const result = await decryptChunk(ciphertext, currentKey);
          const nextPosition = position + result.plaintext.byteLength;
          if (nextPosition > options.fileSize - lastPlainLength) {
            throw new Error("历史加密分片明文超过文件声明范围");
          }
          await writeLegacyPlaintextWithoutManifest({
            plaintext: result.plaintext,
            embeddedHash: result.hash,
            position,
            sink: options.sink,
            metrics,
          });
          position = nextPosition;
          if (index < lastIndex - 1 && !result.nextKey) {
            throw new Error(`历史分片 ${index} 缺少下一分片密钥`);
          }
          currentKey = result.nextKey ?? currentKey;
        } finally {
          metrics.release(ciphertext.byteLength);
        }
        metrics.completedPart();
        options.onPartComplete?.(index + 2, options.totalChunks);
      }
      if (position + lastPlainLength !== options.fileSize) {
        throw new Error(
          `历史加密明文总长度不一致: 期望 ${options.fileSize}，实际 ${position + lastPlainLength}`,
        );
      }
    }

    const snapshot = metrics.snapshot();
    if (snapshot.currentBufferedBytes !== 0) {
      throw new Error(
        `历史下载缓冲区未释放: ${snapshot.currentBufferedBytes} bytes`,
      );
    }
    if (snapshot.bytesWritten !== options.fileSize) {
      throw new Error(
        `历史下载写入长度不一致: 期望 ${options.fileSize}，实际 ${snapshot.bytesWritten}`,
      );
    }
    throwIfDownloadCancelled(options.signal);
    await options.sink.close();
    return metrics.snapshot();
  } catch (error) {
    try {
      await options.sink.abort(error);
    } catch {
      // abort 失败不覆盖原始下载错误。
    }
    throw error;
  } finally {
    options.initialKey = null;
  }
}

/** 执行有界下载；只有全部 part 校验完成后才 close sink。 */
export async function executeBoundedDownload(
  options: BoundedDownloadOptions,
): Promise<DownloadStreamMetrics> {
  const metrics = options.metrics ?? new DownloadMetricsTracker();
  const fetchImpl = options.fetchImpl ?? fetch;
  let transientInitialKey: string | null | undefined =
    options.initialKey ?? options.metadata.initialKey;

  try {
    const parts = validateParts(options.metadata);
    const format = resolveDownloadFormat(options.metadata);
    // 在签发/请求任何对象 URL 前完成 v2 suite、frame 和 nonce 合同校验。
    if (format === "FRAMED_V2") {
      if (!options.metadata.encryption) {
        throw new Error("framed AEAD 文件缺少加密描述");
      }
      validateFramedEncryption(options.metadata.encryption);
    }
    validateCanonicalManifest(
      options.metadata,
      parts,
      format,
      options.expectedFileHash,
    );
    const partFetcher = createRefreshablePartFetcher({
      metadata: options.metadata,
      parts,
      expectedFileHash: options.expectedFileHash,
      signal: options.signal,
      fetchImpl,
      refreshMetadata: options.refreshMetadata,
    });
    if (format === "NONE") {
      const totalCipherSize = parts.reduce((sum, part) => sum + part.size, 0);
      if (totalCipherSize !== options.metadata.fileSize) {
        throw new Error("未加密分片总长度与文件大小不一致");
      }
      for (let index = 0; index < parts.length; index++) {
        const part = parts[index];
        const response = await partFetcher.fetchPart(index);
        await downloadPlainPart({
          response,
          part,
          sink: options.sink,
          metrics,
          signal: options.signal,
        });
        options.onPartComplete?.(index + 1, parts.length);
      }
    } else if (format === "FRAMED_V2") {
      // validateFramedEncryption above proves every required v2 descriptor field.
      const encryption = options.metadata.encryption as ChunkManifestEncryption;
      if (!transientInitialKey) {
        throw new Error("framed AEAD 文件缺少 file DEK");
      }
      const totalPlainSize = parts.reduce(
        (sum, part) => sum + (part.plainSize ?? -1),
        0,
      );
      if (totalPlainSize !== options.metadata.fileSize) {
        throw new Error("framed AEAD 明文总长度与文件大小不一致");
      }
      const fileDek = await importFramedFileDek(transientInitialKey);
      transientInitialKey = null;
      options.initialKey = null;
      for (let index = 0; index < parts.length; index++) {
        const part = parts[index];
        const response = await partFetcher.fetchPart(index);
        await downloadFramedPart({
          response,
          part,
          partCount: parts.length,
          encryption,
          fileDek,
          sink: options.sink,
          metrics,
          signal: options.signal,
        });
        options.onPartComplete?.(index + 1, parts.length);
      }
    } else {
      if (!transientInitialKey) {
        throw new Error("历史加密文件缺少 initialKey");
      }
      await downloadLegacyFile({
        metadata: options.metadata,
        initialKey: transientInitialKey,
        parts,
        sink: options.sink,
        metrics,
        signal: options.signal,
        fetchPart: partFetcher.fetchPart,
        onPartComplete: options.onPartComplete,
      });
    }

    const snapshot = metrics.snapshot();
    if (snapshot.currentBufferedBytes !== 0) {
      throw new Error(
        `下载缓冲区未释放: ${snapshot.currentBufferedBytes} bytes`,
      );
    }
    if (snapshot.bytesWritten !== options.metadata.fileSize) {
      throw new Error(
        `下载明文总长度不一致: 期望 ${options.metadata.fileSize}，实际 ${snapshot.bytesWritten}`,
      );
    }
    throwIfDownloadCancelled(options.signal);
    await options.sink.close();
    return metrics.snapshot();
  } catch (error) {
    try {
      await options.sink.abort(error);
    } catch {
      // abort 失败不覆盖原始下载错误。
    }
    throw error;
  } finally {
    options.initialKey = null;
    options.metadata.initialKey = undefined;
  }
}
