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
  ShareAccessLogVO,
  ShareAccessStatsVO,
  FileProvenanceVO,
} from "../types";
import { ShareType } from "../types";

// Re-export types for use in other modules
export type { FileDecryptInfoVO } from "../types";

const BASE = "/files";

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
  await api.delete(`${BASE}/delete`, {
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
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/hash/${fileHash}/decrypt-info`);
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
 * 获取分享信息。
 *
 * @deprecated 后端未提供此接口，请使用 getSharedFiles 获取分享文件列表
 */
export async function getShareByCode(_code: string): Promise<FileShareVO> {
  throw new Error(
    "后端未提供 GET /files/share/{code} 接口，请使用 getSharedFiles",
  );
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
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  const [chunksBase64, decryptInfo] = await Promise.all([
    downloadEncryptedChunks(fileHash),
    getDecryptInfo(fileHash),
  ]);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);
  return arrayToBlob(decryptedData, decryptInfo.contentType);
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
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(
    `/public/shares/${shareCode}/files/${fileHash}/decrypt-info`,
    {
      skipAuth: true,
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
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  const [chunksBase64, decryptInfo] = await Promise.all([
    publicDownloadEncryptedChunks(shareCode, fileHash),
    publicGetDecryptInfo(shareCode, fileHash),
  ]);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);
  return arrayToBlob(decryptedData, decryptInfo.contentType);
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
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(
    `/shares/${shareCode}/files/${fileHash}/decrypt-info`,
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
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  const [chunksBase64, decryptInfo] = await Promise.all([
    shareDownloadEncryptedChunks(shareCode, fileHash),
    shareGetDecryptInfo(shareCode, fileHash),
  ]);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0)),
  );

  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);
  return arrayToBlob(decryptedData, decryptInfo.contentType);
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
export async function getFileProvenance(fileId: string): Promise<FileProvenanceVO> {
  return api.get<FileProvenanceVO>(`${BASE}/${fileId}/provenance`);
}
