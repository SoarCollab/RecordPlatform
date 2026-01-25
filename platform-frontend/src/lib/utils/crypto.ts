/**
 * 文件分片解密模块
 *
 * 支持解密后端加密的文件分片，实现密钥链机制。
 *
 * 加密分片格式：
 * [版本头: 4B][IV: 12B][加密数据][认证标签][--HASH--\n][hash][--NEXT_KEY--\n][key]
 *
 * 密钥链设计：
 * - chunk[i] 末尾包含 chunk[i+1] 的密钥
 * - 最后一个分片包含 chunk[0] 的密钥（环形）
 * - 解密从最后一个分片开始，使用初始密钥
 */

import { chacha20poly1305 } from "@noble/ciphers/chacha.js";

// 常量定义
const MAGIC_BYTES = new Uint8Array([0x52, 0x50]); // 'RP'
const VERSION = 1;
const IV_SIZE = 12;
const HEADER_SIZE = 4; // Magic(2) + Version(1) + Algorithm(1)

const ALGORITHM_AES_GCM = 0x01;
const ALGORITHM_CHACHA20 = 0x02;

const HASH_SEPARATOR = "\n--HASH--\n";
const KEY_SEPARATOR = "\n--NEXT_KEY--\n";

/**
 * 解析分片头部，获取算法信息
 */
function parseChunkHeader(data: Uint8Array): {
  algorithm: number;
  dataOffset: number;
} {
  if (data.length < HEADER_SIZE) {
    throw new Error("数据太短，无法解析头部");
  }

  // 检查 magic bytes
  if (data[0] !== MAGIC_BYTES[0] || data[1] !== MAGIC_BYTES[1]) {
    throw new Error("无效的文件格式：缺少 magic bytes");
  }

  const version = data[2];
  if (version !== VERSION) {
    throw new Error(`不支持的版本: ${version}`);
  }

  const algorithm = data[3];
  if (algorithm !== ALGORITHM_AES_GCM && algorithm !== ALGORITHM_CHACHA20) {
    throw new Error(`未知的加密算法: ${algorithm}`);
  }

  return { algorithm, dataOffset: HEADER_SIZE };
}

/**
 * 在字节数组中查找子序列的位置（从后往前搜索）
 */
function findBytesLastIndexOf(data: Uint8Array, pattern: Uint8Array): number {
  outer: for (let i = data.length - pattern.length; i >= 0; i--) {
    for (let j = 0; j < pattern.length; j++) {
      if (data[i + j] !== pattern[j]) {
        continue outer;
      }
    }
    return i;
  }
  return -1;
}

/**
 * 从加密分片中提取元数据（哈希和下一个密钥）
 * 直接在字节数组中搜索分隔符，避免 UTF-8 编解码破坏二进制数据
 */
function extractMetadata(data: Uint8Array): {
  encryptedPart: Uint8Array;
  hash: string | null;
  nextKey: string | null;
} {
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  const hashSepBytes = encoder.encode(HASH_SEPARATOR);
  const keySepBytes = encoder.encode(KEY_SEPARATOR);

  let encryptedEndIndex = data.length;
  let hash: string | null = null;
  let nextKey: string | null = null;

  // 查找 KEY_SEPARATOR（从后往前找）
  const keyIndex = findBytesLastIndexOf(data, keySepBytes);
  if (keyIndex !== -1) {
    const keyStart = keyIndex + keySepBytes.length;
    nextKey = decoder.decode(data.slice(keyStart));
    encryptedEndIndex = keyIndex;
  }

  // 查找 HASH_SEPARATOR（在 KEY_SEPARATOR 之前的范围内）
  const searchRange = data.slice(0, encryptedEndIndex);
  const hashIndex = findBytesLastIndexOf(searchRange, hashSepBytes);
  if (hashIndex !== -1) {
    const hashStart = hashIndex + hashSepBytes.length;
    hash = decoder.decode(data.slice(hashStart, encryptedEndIndex));
    encryptedEndIndex = hashIndex;
  }

  return {
    encryptedPart: data.slice(0, encryptedEndIndex),
    hash,
    nextKey,
  };
}

/**
 * AES-GCM 解密
 */
async function decryptAesGcm(
  ciphertext: Uint8Array,
  keyBytes: Uint8Array,
  iv: Uint8Array,
): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey(
    "raw",
    new Uint8Array(keyBytes).buffer as ArrayBuffer,
    { name: "AES-GCM" },
    false,
    ["decrypt"],
  );

  const decrypted = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(iv).buffer as ArrayBuffer },
    key,
    new Uint8Array(ciphertext).buffer as ArrayBuffer,
  );

  return new Uint8Array(decrypted);
}

/**
 * ChaCha20-Poly1305 解密
 */
async function decryptChaCha20(
  ciphertext: Uint8Array,
  keyBytes: Uint8Array,
  nonce: Uint8Array,
): Promise<Uint8Array> {
  const cipher = chacha20poly1305(keyBytes, nonce);
  return cipher.decrypt(ciphertext);
}

/**
 * 解密单个分片
 * 导出以支持流式解密场景
 */
export async function decryptChunk(
  encryptedData: Uint8Array,
  keyBase64: string,
): Promise<{
  plaintext: Uint8Array;
  nextKey: string | null;
  hash: string | null;
}> {
  // 解码密钥
  const keyBytes = Uint8Array.from(atob(keyBase64), (c) => c.charCodeAt(0));

  if (keyBytes.length !== 32) {
    throw new Error(
      `无效的密钥长度: 期望 32 字节，得到 ${keyBytes.length} 字节`,
    );
  }

  // 提取元数据（哈希和下一个密钥）
  const { encryptedPart, hash, nextKey } = extractMetadata(encryptedData);

  // 解析头部
  const { algorithm, dataOffset } = parseChunkHeader(encryptedPart);

  // 提取 IV
  if (encryptedPart.length < dataOffset + IV_SIZE) {
    throw new Error("数据太短，无法提取 IV");
  }
  const iv = encryptedPart.slice(dataOffset, dataOffset + IV_SIZE);

  // 提取密文
  const ciphertext = encryptedPart.slice(dataOffset + IV_SIZE);

  // 根据算法解密
  let plaintext: Uint8Array;
  if (algorithm === ALGORITHM_AES_GCM) {
    plaintext = await decryptAesGcm(ciphertext, keyBytes, iv);
  } else if (algorithm === ALGORITHM_CHACHA20) {
    plaintext = await decryptChaCha20(ciphertext, keyBytes, iv);
  } else {
    throw new Error(`不支持的加密算法: ${algorithm}`);
  }

  return { plaintext, nextKey, hash };
}

/**
 * 解密整个文件（所有分片）
 *
 * @param chunks - 加密的分片数组（按索引顺序：chunk_0, chunk_1, ...）
 * @param initialKey - 最后一个分片的解密密钥（Base64 编码）
 * @returns 解密后的完整文件数据
 */
export async function decryptFile(
  chunks: Uint8Array[],
  initialKey: string,
): Promise<Uint8Array> {
  if (chunks.length === 0) {
    throw new Error("没有分片数据");
  }

  const plaintexts: Uint8Array[] = new Array(chunks.length);
  const totalChunks = chunks.length;

  // 从最后一个分片开始解密
  let currentKey = initialKey;
  let currentIndex = totalChunks - 1;

  // 解密最后一个分片 -> 获取第一个分片的密钥
  const lastResult = await decryptChunk(chunks[currentIndex], currentKey);
  plaintexts[currentIndex] = lastResult.plaintext;

  if (!lastResult.nextKey) {
    throw new Error("最后一个分片缺少下一个密钥");
  }

  // 解密第一个分片（索引 0）
  currentKey = lastResult.nextKey;
  currentIndex = 0;

  while (currentIndex < totalChunks - 1) {
    const result = await decryptChunk(chunks[currentIndex], currentKey);
    plaintexts[currentIndex] = result.plaintext;

    if (currentIndex < totalChunks - 2 && !result.nextKey) {
      throw new Error(`分片 ${currentIndex} 缺少下一个密钥`);
    }

    if (result.nextKey) {
      currentKey = result.nextKey;
    }
    currentIndex++;
  }

  // 合并所有解密后的数据
  const totalLength = plaintexts.reduce((sum, p) => sum + p.length, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const plaintext of plaintexts) {
    result.set(plaintext, offset);
    offset += plaintext.length;
  }

  return result;
}

/**
 * 将 Uint8Array 转换为 Blob
 */
export function arrayToBlob(
  data: Uint8Array,
  mimeType: string = "application/octet-stream",
): Blob {
  return new Blob([new Uint8Array(data).buffer as ArrayBuffer], {
    type: mimeType,
  });
}

/**
 * 触发文件下载
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ===== Streaming Decryption Support =====

/**
 * 解密状态，用于流式解密
 */
export interface StreamingDecryptState {
  totalChunks: number;
  initialKey: string;
  /** 从最后一个分片提取的第一个分片密钥 */
  firstChunkKey: string | null;
  /** 当前已知的各分片密钥（从解密中获得） */
  chunkKeys: Map<number, string>;
}

/**
 * 初始化流式解密状态
 * 需要先解密最后一个分片来获取第一个分片的密钥
 *
 * @param lastChunkData - 最后一个分片的加密数据
 * @param initialKey - 最后一个分片的解密密钥
 * @param totalChunks - 总分片数
 * @returns 解密后的最后一个分片数据和流式解密状态
 */
export async function initStreamingDecrypt(
  lastChunkData: Uint8Array,
  initialKey: string,
  totalChunks: number,
): Promise<{
  lastChunkPlaintext: Uint8Array;
  state: StreamingDecryptState;
}> {
  // 解密最后一个分片获取第一个分片的密钥
  const result = await decryptChunk(lastChunkData, initialKey);

  if (!result.nextKey) {
    throw new Error("最后一个分片缺少下一个密钥");
  }

  const state: StreamingDecryptState = {
    totalChunks,
    initialKey,
    firstChunkKey: result.nextKey,
    chunkKeys: new Map(),
  };

  // 存储第一个分片的密钥
  state.chunkKeys.set(0, result.nextKey);

  return {
    lastChunkPlaintext: result.plaintext,
    state,
  };
}

/**
 * 流式解密单个分片（按顺序）
 * 解密后会更新状态中下一个分片的密钥
 *
 * @param chunkData - 加密的分片数据
 * @param chunkIndex - 分片索引（0-based）
 * @param state - 流式解密状态
 * @returns 解密后的分片数据
 */
export async function streamDecryptChunk(
  chunkData: Uint8Array,
  chunkIndex: number,
  state: StreamingDecryptState,
): Promise<Uint8Array> {
  // 获取当前分片的密钥
  const currentKey = state.chunkKeys.get(chunkIndex);
  if (!currentKey) {
    throw new Error(`分片 ${chunkIndex} 的密钥未知，请确保按顺序解密`);
  }

  // 解密分片
  const result = await decryptChunk(chunkData, currentKey);

  // 存储下一个分片的密钥（如果不是最后一个分片之前的分片）
  if (result.nextKey && chunkIndex < state.totalChunks - 2) {
    state.chunkKeys.set(chunkIndex + 1, result.nextKey);
  }

  // 清理已使用的密钥以释放内存
  state.chunkKeys.delete(chunkIndex);

  return result.plaintext;
}

/**
 * 获取解密顺序
 * 由于密钥链的特性，需要特殊的解密顺序：
 * 1. 首先解密最后一个分片（使用 initialKey）获取第一个分片的密钥
 * 2. 然后按顺序解密 0, 1, 2, ..., n-2
 *
 * @param totalChunks - 总分片数
 * @returns 分片索引数组，表示解密顺序
 */
export function getDecryptionOrder(totalChunks: number): number[] {
  if (totalChunks === 0) return [];
  if (totalChunks === 1) return [0];

  // 最后一个分片先解密，然后是 0 到 n-2
  const order: number[] = [totalChunks - 1];
  for (let i = 0; i < totalChunks - 1; i++) {
    order.push(i);
  }
  return order;
}

/**
 * 获取写入顺序（解密数据应该按什么顺序写入文件）
 * 虽然解密顺序是 [last, 0, 1, 2, ..., n-2]
 * 但写入文件应该是 [0, 1, 2, ..., n-2, last] 即原始顺序
 *
 * @param totalChunks - 总分片数
 * @returns 分片索引数组，表示写入顺序
 */
export function getWriteOrder(totalChunks: number): number[] {
  const order: number[] = [];
  for (let i = 0; i < totalChunks; i++) {
    order.push(i);
  }
  return order;
}

/**
 * 流式解密回调接口
 */
export interface StreamDecryptCallbacks {
  /** 下载单个分片 */
  downloadChunk: (index: number) => Promise<Uint8Array>;
  /** 写入解密后的数据 */
  writeDecrypted: (index: number, data: Uint8Array) => Promise<void>;
  /** 进度回调 */
  onProgress?: (decrypted: number, total: number) => void;
  /** 检查是否取消 */
  isCancelled?: () => boolean;
}

/**
 * 执行完整的流式解密流程
 * 按照正确的顺序下载、解密并写入文件
 *
 * 流程：
 * 1. 下载最后一个分片，解密获取 chunk[0] 的密钥
 * 2. 缓存最后一个分片的解密数据
 * 3. 下载并解密 chunk[0] 到 chunk[n-2]，每个解密后立即写入
 * 4. 最后写入缓存的最后一个分片数据
 *
 * @param totalChunks - 总分片数
 * @param initialKey - 最后一个分片的解密密钥
 * @param callbacks - 回调函数
 */
export async function executeStreamingDecrypt(
  totalChunks: number,
  initialKey: string,
  callbacks: StreamDecryptCallbacks,
): Promise<void> {
  if (totalChunks === 0) {
    throw new Error("没有分片数据");
  }

  const { downloadChunk, writeDecrypted, onProgress, isCancelled } = callbacks;

  // 单分片情况
  if (totalChunks === 1) {
    const data = await downloadChunk(0);
    const result = await decryptChunk(data, initialKey);
    await writeDecrypted(0, result.plaintext);
    onProgress?.(1, 1);
    return;
  }

  // 步骤 1: 下载并解密最后一个分片
  if (isCancelled?.()) throw new Error("Download cancelled");

  const lastChunkIndex = totalChunks - 1;
  const lastChunkData = await downloadChunk(lastChunkIndex);

  const { lastChunkPlaintext, state } = await initStreamingDecrypt(
    lastChunkData,
    initialKey,
    totalChunks,
  );

  let decryptedCount = 1;
  onProgress?.(decryptedCount, totalChunks);

  // 步骤 2-3: 按顺序下载、解密并写入 chunk[0] 到 chunk[n-2]
  for (let i = 0; i < totalChunks - 1; i++) {
    if (isCancelled?.()) throw new Error("Download cancelled");

    const chunkData = await downloadChunk(i);
    const plaintext = await streamDecryptChunk(chunkData, i, state);
    await writeDecrypted(i, plaintext);

    decryptedCount++;
    onProgress?.(decryptedCount, totalChunks);
  }

  // 步骤 4: 写入最后一个分片的解密数据
  await writeDecrypted(lastChunkIndex, lastChunkPlaintext);

  onProgress?.(totalChunks, totalChunks);
}
