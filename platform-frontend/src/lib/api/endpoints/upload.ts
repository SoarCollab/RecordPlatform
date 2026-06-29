import { api } from "../client";
import type {
  StartUploadVO,
  StartUploadRequest,
  ProgressVO,
  FileUploadStatusVO,
  ResumeUploadVO,
  DirectUploadSessionRequest,
  DirectUploadSessionVO,
  DirectUploadCompleteRequest,
  DirectUploadCompleteVO,
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
  if (data.fileId) {
    params.append("fileId", data.fileId);
  }

  return api.post<StartUploadVO>(BASE, params);
}

/**
 * 开始直传上传会话。
 *
 * @param data 直传上传初始化参数
 * @returns 预签名分片上传 URL 列表
 */
export async function startDirectUpload(
  data: DirectUploadSessionRequest,
): Promise<DirectUploadSessionVO> {
  return api.post<DirectUploadSessionVO>(`${BASE}/direct`, data);
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
 * 使用对象存储预签名 URL 直传分片。
 *
 * @param uploadUrl 对象存储预签名 PUT URL
 * @param chunk 分片数据
 * @returns 对象存储返回的 ETag
 */
export async function uploadDirectPart(
  uploadUrl: string,
  chunk: Blob,
): Promise<string | null> {
  const response = await fetch(uploadUrl, {
    method: "PUT",
    body: chunk,
  });

  if (!response.ok) {
    throw new Error(`直传分片失败 (${response.status})`);
  }

  return response.headers.get("ETag");
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
 * 完成直传上传。
 *
 * @param clientId 客户端会话 ID
 * @param data 已直传分片的完成元数据
 * @returns 文件入库和 manifest 持久化结果
 */
export async function completeDirectUpload(
  clientId: string,
  data: DirectUploadCompleteRequest,
): Promise<DirectUploadCompleteVO> {
  return api.post<DirectUploadCompleteVO>(
    `${BASE}/${clientId}/direct/complete`,
    data,
  );
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
 * 取消直传上传并清理对象存储 staging 分片。
 *
 * @param clientId 客户端会话 ID
 */
export async function abortDirectUpload(clientId: string): Promise<void> {
  await api.delete(`${BASE}/${clientId}/direct`);
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
