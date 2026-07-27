import { api } from "../client";
import type {
  Page,
  PageParams,
  FileVO,
  FileQueryParams,
  FileShareVO,
  TransactionVO,
  SharedFileVO,
  SaveShareFileRequest,
  FileDecryptInfoVO,
  CreateShareRequest,
  UpdateShareRequest,
  UserFileStatsVO,
  QuotaStatusVO,
  BatchDownloadMetricsReportRequest,
  ShareAccessLogVO,
  ShareAccessStatsVO,
  FileProvenanceVO,
  FileDownloadMetadataVO,
  DownloadKeyGrantVO,
  DownloadKeyMaterialVO,
} from "../types";
import { ShareType } from "../types";

// Re-export types for use in other modules
export type { FileDecryptInfoVO, FileDownloadMetadataVO } from "../types";

const BASE = "/files";

const KEY_DELIVERY_PROTOCOL = "grant-v1";

/**
 * 创建仅保存在当前下载执行作用域内的随机会话标识。
 */
export function createDownloadSessionId(): string {
  const bytes = new Uint8Array(24);
  globalThis.crypto.getRandomValues(bytes);
  try {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary)
      .replaceAll("+", "-")
      .replaceAll("/", "_")
      .replace(/=+$/u, "");
  } finally {
    bytes.fill(0);
  }
}

/**
 * 通过 POST 请求体即时消费 grant，reference 与密钥均不进入 URL。
 */
export async function consumeDownloadKeyGrant(
  grant: DownloadKeyGrantVO,
  sessionId: string,
  publicAccess = false,
): Promise<DownloadKeyMaterialVO> {
  if (grant.protocol !== KEY_DELIVERY_PROTOCOL) {
    throw new Error("不支持的下载密钥授权协议");
  }
  const path = publicAccess
    ? "/public/key-grants/consume"
    : `${BASE}/key-grants/consume`;
  return api.post<DownloadKeyMaterialVO>(
    path,
    { grantReference: grant.reference, sessionId },
    publicAccess
      ? { skipAuth: true, skipTenant: true, retries: 1 }
      : { retries: 1 },
  );
}

/**
 * 即时解析 grant-v1 或受控 plaintext-v0 响应中的密钥。
 */
async function resolveInitialKey(
  decryptInfo: FileDecryptInfoVO,
  sessionId: string,
  publicAccess = false,
): Promise<string> {
  if (decryptInfo.keyGrant) {
    const keyMaterial = await consumeDownloadKeyGrant(
      decryptInfo.keyGrant,
      sessionId,
      publicAccess,
    );
    if (!keyMaterial.initialKey) {
      throw new Error("下载密钥授权未返回可用密钥");
    }
    if (keyMaterial.protocol !== decryptInfo.keyGrant.protocol) {
      throw new Error("下载密钥授权协议不一致");
    }
    return keyMaterial.initialKey;
  }
  if (decryptInfo.initialKey) {
    return decryptInfo.initialKey;
  }
  throw new Error("缺少加密文件下载密钥授权");
}

/**
 * 将未加密分片按服务端顺序合并，避免 NONE 文件被错误地要求提供 grant。
 */
function concatenatePlainChunks(chunks: Uint8Array[]): Uint8Array {
  if (chunks.length === 0) {
    throw new Error("没有分片数据");
  }
  const totalLength = chunks.reduce((sum, chunk) => {
    const next = sum + chunk.byteLength;
    if (!Number.isSafeInteger(next)) {
      throw new Error("文件分片总长度超出安全范围");
    }
    return next;
  }, 0);
  const result = new Uint8Array(totalLength);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}

/**
 * 在分片下载完成后即时消费加密 grant；NONE 文件则直接合并原始分片。
 */
async function materializeDownloadedChunks(
  chunks: Uint8Array[],
  decryptInfo: FileDecryptInfoVO,
  sessionId: string,
  publicAccess = false,
): Promise<Blob> {
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");
  if (!decryptInfo.keyGrant && !decryptInfo.initialKey) {
    return arrayToBlob(concatenatePlainChunks(chunks), decryptInfo.contentType);
  }

  const initialKey = await resolveInitialKey(
    decryptInfo,
    sessionId,
    publicAccess,
  );
  const decryptedData = await decryptFile(chunks, initialKey);
  return arrayToBlob(decryptedData, decryptInfo.contentType);
}

/**
 * 获取文件列表（分页）。
 *
 * @param params 查询参数
 * @returns 文件分页
 */
export async function getFiles(
  params?: PageParams & FileQueryParams,
): Promise<Page<FileVO>> {
  return api.get<Page<FileVO>>(BASE, { params });
}

/**
 * 获取用户文件统计信息（用于 Dashboard）。
 *
 * @returns 统计信息
 */
export async function getUserFileStats(): Promise<UserFileStatsVO> {
  return api.get<UserFileStatsVO>(`${BASE}/stats`);
}

/**
 * 获取当前配额状态。
 *
 * @returns 配额状态
 */
export async function getQuotaStatus(): Promise<QuotaStatusVO> {
  return api.get<QuotaStatusVO>(`${BASE}/quota`);
}

/**
 * 获取单个文件信息。
 *
 * @param id 文件 ID
 * @returns 文件详情
 */
export async function getFile(id: string): Promise<FileVO> {
  return api.get<FileVO>(`${BASE}/${id}`);
}

/**
 * 通过哈希获取文件信息。
 *
 * @param hash 文件哈希
 * @returns 文件详情
 */
export async function getFileByHash(hash: string): Promise<FileVO> {
  return api.get<FileVO>(`${BASE}/hash/${hash}`);
}

/**
 * 删除文件。
 *
 * @param fileHashOrId 文件哈希或文件 ID
 */
export async function deleteFile(fileHashOrId: string): Promise<void> {
  await api.delete(BASE, {
    params: { identifiers: [fileHashOrId] },
  });
}

/**
 * 下载文件（获取加密分片）。
 *
 * @param fileHash 文件哈希
 * @returns 加密分片 Base64 数组
 */
export async function downloadEncryptedChunks(
  fileHash: string,
): Promise<string[]> {
  return api.get<string[]>(`${BASE}/hash/${fileHash}/chunks`);
}

/**
 * 获取文件解密信息。
 *
 * @param fileHash 文件哈希
 * @returns 解密信息
 */
export async function getDecryptInfo(
  fileHash: string,
  sessionId: string,
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/hash/${fileHash}/decrypt-info`, {
    headers: {
      "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
      "X-Download-Session-ID": sessionId,
    },
  });
}

/**
 * 创建文件分享。
 *
 * @param payload 分享参数
 * @returns 分享码
 */
export async function createShare(
  payload: CreateShareRequest,
): Promise<string> {
  return api.post<string>("/shares", payload);
}

/**
 * 更新分享设置（类型/有效期）。
 *
 * @param payload 更新参数
 */
export async function updateShare(payload: UpdateShareRequest): Promise<void> {
  if (!payload.shareCode) {
    throw new Error("shareCode 不能为空");
  }
  await api.patch(`/shares/${payload.shareCode}`, payload);
}

/**
 * 获取分享文件列表。
 *
 * @param sharingCode 分享码
 * @returns 分享文件列表
 */
export async function getSharedFiles(
  sharingCode: string,
): Promise<SharedFileVO[]> {
  return api.get<SharedFileVO[]>(`/shares/${sharingCode}/files`, {
    skipAuth: true,
    skipTenant: true,
  });
}

/**
 * 取消分享。
 *
 * @param shareCode 分享码
 */
export async function cancelShare(shareCode: string): Promise<void> {
  await api.delete(`${BASE}/share/${shareCode}`);
}

/**
 * 获取我的分享列表。
 *
 * @param params 分页参数
 * @returns 分享分页
 */
export async function getMyShares(
  params?: PageParams,
): Promise<Page<FileShareVO>> {
  return api.get<Page<FileShareVO>>(`${BASE}/shares`, { params });
}

/**
 * 获取文件下载地址（预签名 URL 列表）。
 *
 * @param fileHash 文件哈希
 * @returns 地址列表
 */
export async function getDownloadAddress(fileHash: string): Promise<string[]> {
  return api.get<string[]>(`${BASE}/hash/${fileHash}/addresses`);
}

/**
 * 获取文件预签名分片下载元数据。
 *
 * @param fileHash 文件哈希
 * @returns manifest、解密信息和有序分片下载 URL
 */
export async function getDownloadMetadata(
  fileHash: string,
  sessionId: string,
): Promise<FileDownloadMetadataVO> {
  return api.get<FileDownloadMetadataVO>(
    `${BASE}/hash/${fileHash}/download-metadata`,
    {
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
}

/**
 * 上报批量下载质量指标。
 *
 * @param payload 批次质量聚合指标。
 * @returns 后端固定响应 `ok`。
 */
export async function reportBatchDownloadMetrics(
  payload: BatchDownloadMetricsReportRequest,
): Promise<string> {
  return api.post<string>(`${BASE}/download-batches/report`, payload);
}

/**
 * 获取区块链交易记录。
 *
 * @param transactionHash 交易哈希
 * @returns 交易记录
 */
export async function getTransaction(
  transactionHash: string,
): Promise<TransactionVO> {
  return api.get<TransactionVO>(`/transactions/${transactionHash}`);
}

/**
 * 保存分享文件到我的账户。
 *
 * @param request 保存参数
 */
export async function saveSharedFiles(
  request: SaveShareFileRequest,
): Promise<void> {
  await api.post(`/shares/${request.shareCode}/files/save`, request);
}

/**
 * 下载并解密文件。
 *
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function downloadFile(fileHash: string): Promise<Blob> {
  const chunksBase64 = await downloadEncryptedChunks(fileHash);
  const sessionId = createDownloadSessionId();
  const decryptInfo = await getDecryptInfo(fileHash, sessionId);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  return materializeDownloadedChunks(chunks, decryptInfo, sessionId);
}

// ==================== 公开分享端点（无需认证）====================

/**
 * 公开分享下载加密分片（无需登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 分片数组
 */
export async function publicDownloadEncryptedChunks(
  shareCode: string,
  fileHash: string,
): Promise<string[]> {
  return api.get<string[]>(
    `/public/shares/${shareCode}/files/${fileHash}/chunks`,
    {
      skipAuth: true,
      skipTenant: true,
    },
  );
}

/**
 * 公开分享获取解密信息（无需登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密信息
 */
export async function publicGetDecryptInfo(
  shareCode: string,
  fileHash: string,
  sessionId: string,
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(
    `/public/shares/${shareCode}/files/${fileHash}/decrypt-info`,
    {
      skipAuth: true,
      skipTenant: true,
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
}

/**
 * 公开分享下载并解密文件（无需登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function publicDownloadFile(
  shareCode: string,
  fileHash: string,
): Promise<Blob> {
  const chunksBase64 = await publicDownloadEncryptedChunks(shareCode, fileHash);
  const sessionId = createDownloadSessionId();
  const decryptInfo = await publicGetDecryptInfo(
    shareCode,
    fileHash,
    sessionId,
  );

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  return materializeDownloadedChunks(chunks, decryptInfo, sessionId, true);
}

/**
 * 登录用户分享下载加密分片（需要登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 分片数组
 */
export async function shareDownloadEncryptedChunks(
  shareCode: string,
  fileHash: string,
): Promise<string[]> {
  return api.get<string[]>(`/shares/${shareCode}/files/${fileHash}/chunks`);
}

/**
 * 登录用户分享获取解密信息（需要登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密信息
 */
export async function shareGetDecryptInfo(
  shareCode: string,
  fileHash: string,
  sessionId: string,
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(
    `/shares/${shareCode}/files/${fileHash}/decrypt-info`,
    {
      headers: {
        "X-Key-Delivery-Protocol": KEY_DELIVERY_PROTOCOL,
        "X-Download-Session-ID": sessionId,
      },
    },
  );
}

/**
 * 登录用户通过分享码下载并解密文件（需要登录）。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function shareDownloadFile(
  shareCode: string,
  fileHash: string,
): Promise<Blob> {
  const chunksBase64 = await shareDownloadEncryptedChunks(shareCode, fileHash);
  const sessionId = createDownloadSessionId();
  const decryptInfo = await shareGetDecryptInfo(shareCode, fileHash, sessionId);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  return materializeDownloadedChunks(chunks, decryptInfo, sessionId);
}

/**
 * 根据分享类型选择下载方式。
 *
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @param shareType 分享类型
 * @returns 解密后的 Blob
 */
export async function downloadSharedFile(
  shareCode: string,
  fileHash: string,
  shareType: ShareType,
): Promise<Blob> {
  if (shareType === ShareType.PUBLIC) {
    return publicDownloadFile(shareCode, fileHash);
  }
  return shareDownloadFile(shareCode, fileHash);
}

// ==================== 审计端点 ====================

/**
 * 获取分享访问日志。
 *
 * @param shareCode 分享码
 * @param params 分页参数
 * @returns 访问日志分页
 */
export async function getShareAccessLogs(
  shareCode: string,
  params?: PageParams,
): Promise<Page<ShareAccessLogVO>> {
  return api.get<Page<ShareAccessLogVO>>(
    `${BASE}/share/${shareCode}/access-logs`,
    {
      params,
    },
  );
}

/**
 * 获取分享访问统计。
 *
 * @param shareCode 分享码
 * @returns 统计数据
 */
export async function getShareAccessStats(
  shareCode: string,
): Promise<ShareAccessStatsVO> {
  return api.get<ShareAccessStatsVO>(`${BASE}/share/${shareCode}/stats`);
}

/**
 * 获取文件溯源信息。
 *
 * @param fileId 文件 ID
 * @returns 溯源信息
 */
export async function getFileProvenance(
  fileId: string,
): Promise<FileProvenanceVO> {
  return api.get<FileProvenanceVO>(`${BASE}/${fileId}/provenance`);
}
