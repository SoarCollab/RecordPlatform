import { api } from '../client';
import type { StartUploadVO, StartUploadRequest, ProgressVO } from '../types';

const BASE = '/files/upload';

/**
 * 开始上传 (初始化或恢复)
 */
export async function startUpload(data: StartUploadRequest): Promise<StartUploadVO> {
	return api.post<StartUploadVO>(`${BASE}/start`, data);
}

/**
 * 上传分片
 */
export async function uploadChunk(
	clientId: string,
	chunkNumber: number,
	chunk: Blob
): Promise<void> {
	const formData = new FormData();
	formData.append('clientId', clientId);
	formData.append('chunkNumber', String(chunkNumber));
	formData.append('file', chunk);

	return api.upload(`${BASE}/chunk`, formData);
}

/**
 * 完成上传
 */
export async function completeUpload(clientId: string): Promise<void> {
	return api.post(`${BASE}/complete`, { clientId });
}

/**
 * 暂停上传
 */
export async function pauseUpload(clientId: string): Promise<void> {
	return api.post(`${BASE}/pause`, { clientId });
}

/**
 * 恢复上传
 */
export async function resumeUpload(clientId: string): Promise<StartUploadVO> {
	return api.post<StartUploadVO>(`${BASE}/resume`, { clientId });
}

/**
 * 取消上传
 */
export async function cancelUpload(clientId: string): Promise<void> {
	return api.post(`${BASE}/cancel`, { clientId });
}

/**
 * 检查上传状态
 */
export async function checkUploadStatus(
	fileName: string,
	fileSize: number,
	fileHash?: string
): Promise<StartUploadVO | null> {
	return api.get<StartUploadVO | null>(`${BASE}/check`, {
		params: { fileName, fileSize, fileHash }
	});
}

/**
 * 获取上传进度
 */
export async function getUploadProgress(clientId: string): Promise<ProgressVO> {
	return api.get<ProgressVO>(`${BASE}/progress`, { params: { clientId } });
}
