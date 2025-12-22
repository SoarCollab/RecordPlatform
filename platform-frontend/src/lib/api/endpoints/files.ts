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
} from "../types";

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
export async function createShare(payload: {
  fileHash: string[];
  /** 分享有效期（分钟），范围：1-43200 */
  expireMinutes: number;
}): Promise<string> {
  return api.post<string>(`${BASE}/share`, payload);
}

/**
 * 获取分享信息
 * @deprecated 后端未提供此接口，请使用 getSharedFiles 获取分享的文件列表
 */
export async function getShareByCode(code: string): Promise<FileShareVO> {
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
