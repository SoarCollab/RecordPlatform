import { api } from "../client";
import type {
  StartUploadVO,
  StartUploadRequest,
  ProgressVO,
  FileUploadStatusVO,
  ResumeUploadVO,
} from "../types";

const BASE = "/files/upload";

/**
 * 开始上传 (初始化或恢复)
 * @note 后端接口使用 @RequestParam，需要发送 application/x-www-form-urlencoded 或 query params
 */
export async function startUpload(
  data: StartUploadRequest
): Promise<StartUploadVO> {
  // 构建 URLSearchParams 适配后端 @RequestParam
  const params = new URLSearchParams();
  params.append("fileName", data.fileName);
  params.append("fileSize", String(data.fileSize));
  params.append("contentType", data.contentType);
  params.append("totalChunks", String(data.totalChunks));
  params.append("chunkSize", String(data.chunkSize));

  return api.post<StartUploadVO>(`${BASE}/start`, params);
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
  formData.append("clientId", clientId);
  formData.append("chunkNumber", String(chunkNumber));
  formData.append("file", chunk);

  return api.upload(`${BASE}/chunk`, formData);
}

/**
 * 完成上传
 * @note 后端使用 @RequestParam("clientId")
 */
export async function completeUpload(clientId: string): Promise<void> {
  return api.post(`${BASE}/complete`, null, { params: { clientId } });
}

/**
 * 暂停上传
 * @note 后端使用 @RequestParam("clientId")
 */
export async function pauseUpload(clientId: string): Promise<void> {
  return api.post(`${BASE}/pause`, null, { params: { clientId } });
}

/**
 * 恢复上传
 * @note 后端使用 @RequestParam("clientId")，返回 ResumeUploadVO
 */
export async function resumeUpload(clientId: string): Promise<ResumeUploadVO> {
  return api.post<ResumeUploadVO>(`${BASE}/resume`, null, {
    params: { clientId },
  });
}

/**
 * 取消上传
 * @note 后端使用 @RequestParam("clientId")
 */
export async function cancelUpload(clientId: string): Promise<void> {
  return api.post(`${BASE}/cancel`, null, { params: { clientId } });
}

/**
 * 检查上传状态
 * @note 后端使用 clientId 而非 fileName/fileSize/fileHash
 */
export async function checkUploadStatus(
  clientId: string
): Promise<FileUploadStatusVO | null> {
  return api.get<FileUploadStatusVO | null>(`${BASE}/check`, {
    params: { clientId },
  });
}

/**
 * 获取上传进度
 */
export async function getUploadProgress(clientId: string): Promise<ProgressVO> {
  return api.get<ProgressVO>(`${BASE}/progress`, { params: { clientId } });
}
