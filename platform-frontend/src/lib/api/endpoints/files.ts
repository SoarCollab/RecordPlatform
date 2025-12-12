import { api } from '../client';
import type {
	Page,
	PageParams,
	FileVO,
	FileQueryParams,
	SharingVO,
	CreateShareRequest,
	TransactionVO,
	SharedFileVO,
	SaveShareFileRequest,
	FileDecryptInfoVO
} from '../types';

const BASE = '/files';

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
 */
export async function getFile(id: string): Promise<FileVO> {
	return api.get<FileVO>(`${BASE}/${id}`);
}

/**
 * 通过哈希获取文件
 */
export async function getFileByHash(hash: string): Promise<FileVO> {
	return api.get<FileVO>(`${BASE}/address`, { params: { hash } });
}

/**
 * 删除文件
 */
export async function deleteFile(id: string): Promise<void> {
	return api.delete(`${BASE}/${id}`);
}

/**
 * 下载文件（获取加密分片）
 * @param fileHash 文件哈希
 * @returns 加密分片的 Base64 字符串数组
 */
export async function downloadEncryptedChunks(fileHash: string): Promise<string[]> {
	return api.get<string[]>(`${BASE}/download`, { params: { fileHash } });
}

/**
 * 获取文件解密信息
 * @param fileHash 文件哈希
 * @returns 解密信息（包含初始密钥）
 */
export async function getDecryptInfo(fileHash: string): Promise<FileDecryptInfoVO> {
	return api.get<FileDecryptInfoVO>(`${BASE}/decryptInfo`, { params: { fileHash } });
}

/**
 * 创建文件分享
 */
export async function createShare(data: CreateShareRequest): Promise<SharingVO> {
	return api.post<SharingVO>(`${BASE}/share`, data);
}

/**
 * 获取分享信息
 */
export async function getShareByCode(code: string): Promise<SharingVO> {
	return api.get<SharingVO>(`${BASE}/share/${code}`, { skipAuth: true });
}

/**
 * 获取分享的文件列表
 */
export async function getSharedFiles(code: string, password?: string): Promise<SharedFileVO[]> {
	return api.get<SharedFileVO[]>(`${BASE}/getSharingFiles`, {
		params: { code, password },
		skipAuth: true
	});
}

/**
 * 取消分享
 */
export async function cancelShare(shareId: string): Promise<void> {
	return api.delete(`${BASE}/share/${shareId}`);
}

/**
 * 获取我的分享列表
 */
export async function getMyShares(params?: PageParams): Promise<Page<SharingVO>> {
	return api.get<Page<SharingVO>>(`${BASE}/shares`, { params });
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
export async function getTransaction(transactionHash: string): Promise<TransactionVO> {
	return api.get<TransactionVO>(`${BASE}/getTransaction`, { params: { transactionHash } });
}

/**
 * 保存分享的文件到我的账户
 * @param request 要保存的文件 ID 列表
 */
export async function saveSharedFiles(request: SaveShareFileRequest): Promise<void> {
	return api.post(`${BASE}/saveShareFile`, request);
}

/**
 * 创建文件分享 (多文件)
 * @param fileHash 文件哈希列表
 * @param maxAccesses 最大访问次数
 */
export async function shareFiles(fileHash: string[], maxAccesses?: number): Promise<string> {
	return api.post<string>(`${BASE}/share`, { fileHash, maxAccesses });
}

/**
 * 下载并解密文件
 * @param fileHash 文件哈希
 * @returns 解密后的 Blob
 */
export async function downloadFile(fileHash: string): Promise<Blob> {
	const { decryptFile, arrayToBlob } = await import('$utils/crypto');

	// 并行获取加密分片和解密信息
	const [chunksBase64, decryptInfo] = await Promise.all([
		downloadEncryptedChunks(fileHash),
		getDecryptInfo(fileHash)
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
