import { api } from "../client";
import type {
  StartUploadVO,
  StartUploadRequest,
  ProgressVO,
  FileUploadStatusVO,
  ResumeUploadVO,
} from "../types";

const BASE = "/upload-sessions";

/**
 * 开始上传（初始化或恢复）。
 *
 * @param data 上传初始化参数
 * @returns 上传会话信息
 */
export async function startUpload(
  data: StartUploadRequest,
): Promise<StartUploadVO> {
  const params = new URLSearchParams();
  params.append("fileName", data.fileName);
  params.append("fileSize", String(data.fileSize));
  params.append("contentType", data.contentType);
  params.append("totalChunks", String(data.totalChunks));
  params.append("chunkSize", String(data.chunkSize));

  return api.post<StartUploadVO>(BASE, params);
}

/**
 * 上传分片。
 *
 * @param clientId 客户端会话 ID
 * @param chunkNumber 分片序号
 * @param chunk 分片数据
 */
export async function uploadChunk(
  clientId: string,
  chunkNumber: number,
  chunk: Blob,
): Promise<void> {
  const formData = new FormData();
  formData.append("file", chunk);

  await api.put(`${BASE}/${clientId}/chunks/${chunkNumber}`, formData);
}

/**
 * 完成上传。
 *
 * @param clientId 客户端会话 ID
 */
export async function completeUpload(clientId: string): Promise<void> {
  await api.post(`${BASE}/${clientId}/complete`);
}

/**
 * 暂停上传。
 *
 * @param clientId 客户端会话 ID
 */
export async function pauseUpload(clientId: string): Promise<void> {
  await api.post(`${BASE}/${clientId}/pause`);
}

/**
 * 恢复上传。
 *
 * @param clientId 客户端会话 ID
 * @returns 恢复结果
 */
export async function resumeUpload(clientId: string): Promise<ResumeUploadVO> {
  return api.post<ResumeUploadVO>(`${BASE}/${clientId}/resume`);
}

/**
 * 取消上传。
 *
 * @param clientId 客户端会话 ID
 */
export async function cancelUpload(clientId: string): Promise<void> {
  await api.delete(`${BASE}/${clientId}`);
}

/**
 * 检查上传状态。
 *
 * @param clientId 客户端会话 ID
 * @returns 上传状态
 */
export async function checkUploadStatus(
  clientId: string,
): Promise<FileUploadStatusVO | null> {
  return api.get<FileUploadStatusVO | null>(`${BASE}/${clientId}`);
}

/**
 * 获取上传进度。
 *
 * @param clientId 客户端会话 ID
 * @returns 进度信息
 */
export async function getUploadProgress(clientId: string): Promise<ProgressVO> {
  return api.get<ProgressVO>(`${BASE}/${clientId}/progress`);
}
