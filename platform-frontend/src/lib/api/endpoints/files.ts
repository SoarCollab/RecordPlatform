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
} from "../types";
import { ShareType } from "../types";

// Re-export types for use in other modules
export type { FileDecryptInfoVO } from "../types";

const BASE = "/files";

/**
 * 获取文件列表 (分页)
 */
export async function getFiles(
  params?: PageParams & FileQueryParams
): Promise<Page<FileVO>> {
  return api.get<Page<FileVO>>(`${BASE}/page`, { params });
}

/**
 * 获取用户文件统计信息（用于 Dashboard）
 * @see FileController.getUserFileStats
 */
export async function getUserFileStats(): Promise<UserFileStatsVO> {
  return api.get<UserFileStatsVO>(`${BASE}/stats`);
}

/**
 * 获取单个文件信息
 * @see FileController.getFileById
 */
export async function getFile(id: string): Promise<FileVO> {
  return api.get<FileVO>(`${BASE}/${id}`);
}

/**
 * 通过哈希获取文件信息
 * @deprecated 后端 /files/address 接口返回的是下载地址列表而非 FileVO
 * @todo 后端需要添加 GET /files/byHash?hash={hash} 返回 FileVO 的接口
 * @note 临时方案：使用 getFiles 分页接口并过滤
 */
export async function getFileByHash(hash: string): Promise<FileVO> {
  // 临时方案：通过分页接口获取并过滤
  const page = await getFiles({ pageNum: 1, pageSize: 100 });
  const file = page.records.find((f) => f.fileHash === hash);
  if (!file) {
    throw new Error(`找不到文件: ${hash}`);
  }
  return file;
}

/**
 * 删除文件
 * @note 后端接口: DELETE /files/delete (同时支持 hash 和 id)
 * @param fileHashOrId 文件哈希或文件ID
 */
export async function deleteFile(fileHashOrId: string): Promise<void> {
  return api.delete(`${BASE}/delete`, {
    params: { identifiers: [fileHashOrId] },
  });
}

/**
 * 下载文件（获取加密分片）
 * @param fileHash 文件哈希
 * @returns 加密分片的 Base64 字符串数组
 */
export async function downloadEncryptedChunks(
  fileHash: string
): Promise<string[]> {
  return api.get<string[]>(`${BASE}/download`, { params: { fileHash } });
}

/**
 * 获取文件解密信息
 * @param fileHash 文件哈希
 * @returns 解密信息（包含初始密钥）
 */
export async function getDecryptInfo(
  fileHash: string
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/decryptInfo`, {
    params: { fileHash },
  });
}

/**
 * 创建文件分享
 * @see FileController.generateSharingCode
 * @returns 分享码字符串
 */
export async function createShare(
  payload: CreateShareRequest
): Promise<string> {
  return api.post<string>(`${BASE}/share`, payload);
}

/**
 * 更新分享设置（类型/有效期）
 * @see FileController.updateShare
 */
export async function updateShare(payload: UpdateShareRequest): Promise<void> {
  return api.put(`${BASE}/share`, payload);
}

/**
 * 获取分享信息
 * @deprecated 后端未提供此接口，请使用 getSharedFiles 获取分享的文件列表
 */
export async function getShareByCode(_code: string): Promise<FileShareVO> {
  throw new Error(
    "后端未提供 GET /files/share/{code} 接口，请使用 getSharedFiles"
  );
}

/**
 * 获取分享的文件列表
 * @see FileController.getShareFile
 * @param sharingCode 分享码
 */
export async function getSharedFiles(
  sharingCode: string
): Promise<SharedFileVO[]> {
  return api.get<SharedFileVO[]>(`${BASE}/getSharingFiles`, {
    params: { sharingCode },
    skipAuth: true,
  });
}

/**
 * 取消分享
 * @see FileController.cancelShare
 * @param shareCode 分享码
 */
export async function cancelShare(shareCode: string): Promise<void> {
  return api.delete(`${BASE}/share/${shareCode}`);
}

/**
 * 获取我的分享列表
 * @see FileController.getMyShares
 * @see FileShareVO.java
 */
export async function getMyShares(
  params?: PageParams
): Promise<Page<FileShareVO>> {
  return api.get<Page<FileShareVO>>(`${BASE}/shares`, { params });
}

/**
 * 获取文件下载地址 (预签名URL列表)
 * @param fileHash 文件哈希
 */
export async function getDownloadAddress(fileHash: string): Promise<string[]> {
  return api.get<string[]>(`${BASE}/address`, { params: { fileHash } });
}

/**
 * 获取区块链交易记录
 * @param transactionHash 交易哈希
 */
export async function getTransaction(
  transactionHash: string
): Promise<TransactionVO> {
  return api.get<TransactionVO>(`${BASE}/getTransaction`, {
    params: { transactionHash },
  });
}

/**
 * 保存分享的文件到我的账户
 * @param request 要保存的文件 ID 列表
 */
export async function saveSharedFiles(
  request: SaveShareFileRequest
): Promise<void> {
  return api.post(`${BASE}/saveShareFile`, request);
}

/**
 * 下载并解密文件
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function downloadFile(fileHash: string): Promise<Blob> {
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  // 并行获取加密分片和解密信息
  const [chunksBase64, decryptInfo] = await Promise.all([
    downloadEncryptedChunks(fileHash),
    getDecryptInfo(fileHash),
  ]);

  // Base64 解码为 Uint8Array
  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  );

  // 解密文件
  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);

  // 转换为 Blob
  return arrayToBlob(decryptedData, decryptInfo.contentType);
}

// ==================== 公开分享端点（无需认证）====================

/**
 * 公开分享下载加密分片（无需登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 加密分片的 Base64 字符串数组
 */
export async function publicDownloadEncryptedChunks(
  shareCode: string,
  fileHash: string
): Promise<string[]> {
  return api.get<string[]>(`${BASE}/public/download`, {
    params: { shareCode, fileHash },
    skipAuth: true,
  });
}

/**
 * 公开分享获取解密信息（无需登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密信息（包含初始密钥）
 */
export async function publicGetDecryptInfo(
  shareCode: string,
  fileHash: string
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/public/decryptInfo`, {
    params: { shareCode, fileHash },
    skipAuth: true,
  });
}

/**
 * 公开分享下载并解密文件（无需登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function publicDownloadFile(
  shareCode: string,
  fileHash: string
): Promise<Blob> {
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  // 并行获取加密分片和解密信息
  const [chunksBase64, decryptInfo] = await Promise.all([
    publicDownloadEncryptedChunks(shareCode, fileHash),
    publicGetDecryptInfo(shareCode, fileHash),
  ]);

  // Base64 解码为 Uint8Array
  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  );

  // 解密文件
  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);

  // 转换为 Blob
  return arrayToBlob(decryptedData, decryptInfo.contentType);
}

/**
 * 登录用户分享下载加密分片（需要登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 加密分片的 Base64 字符串数组
 */
export async function shareDownloadEncryptedChunks(
  shareCode: string,
  fileHash: string
): Promise<string[]> {
  return api.get<string[]>(`${BASE}/share/download`, {
    params: { shareCode, fileHash },
  });
}

/**
 * 登录用户分享获取解密信息（需要登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密信息（包含初始密钥）
 */
export async function shareGetDecryptInfo(
  shareCode: string,
  fileHash: string
): Promise<FileDecryptInfoVO> {
  return api.get<FileDecryptInfoVO>(`${BASE}/share/decryptInfo`, {
    params: { shareCode, fileHash },
  });
}

/**
 * 登录用户通过分享码下载并解密文件（需要登录）
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function shareDownloadFile(
  shareCode: string,
  fileHash: string
): Promise<Blob> {
  const { decryptFile, arrayToBlob } = await import("$utils/crypto");

  const [chunksBase64, decryptInfo] = await Promise.all([
    shareDownloadEncryptedChunks(shareCode, fileHash),
    shareGetDecryptInfo(shareCode, fileHash),
  ]);

  const chunks = chunksBase64.map((base64) =>
    Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
  );

  const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);

  return arrayToBlob(decryptedData, decryptInfo.contentType);
}

/**
 * 根据分享类型选择下载方式
 * @param shareCode 分享码
 * @param fileHash 文件哈希
 * @param shareType 分享类型
 * @returns 解密后的 Blob
 */
export async function downloadSharedFile(
  shareCode: string,
  fileHash: string,
  shareType: ShareType
): Promise<Blob> {
  if (shareType === ShareType.PUBLIC) {
    return publicDownloadFile(shareCode, fileHash);
  } else {
    // 私密分享需要登录，通过分享码下载
    return shareDownloadFile(shareCode, fileHash);
  }
}
