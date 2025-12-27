import { api } from "../client";
import type {
  Page,
  PageParams,
  AdminFileVO,
  AdminFileDetailVO,
  AdminShareVO,
  AdminFileQueryParams,
  AdminShareQueryParams,
  UpdateFileStatusRequest,
  ShareAccessLogVO,
  ShareAccessStatsVO,
} from "../types";

const BASE = "/admin/files";

// ==================== 文件管理 ====================

/**
 * 获取所有文件列表（分页）
 */
export async function getAllFiles(
  params?: PageParams & AdminFileQueryParams
): Promise<Page<AdminFileVO>> {
  return api.get<Page<AdminFileVO>>(BASE, { params });
}

/**
 * 获取文件详情（含审计信息）
 */
export async function getFileDetail(id: string): Promise<AdminFileDetailVO> {
  return api.get<AdminFileDetailVO>(`${BASE}/${id}`);
}

/**
 * 更新文件状态
 */
export async function updateFileStatus(
  id: string,
  request: UpdateFileStatusRequest
): Promise<void> {
  return api.put(`${BASE}/${id}/status`, request);
}

/**
 * 强制删除文件（物理删除）
 */
export async function forceDeleteFile(
  id: string,
  reason?: string
): Promise<void> {
  return api.delete(`${BASE}/${id}`, { params: { reason } });
}

// ==================== 分享管理 ====================

/**
 * 获取所有分享列表（分页）
 */
export async function getAllShares(
  params?: PageParams & AdminShareQueryParams
): Promise<Page<AdminShareVO>> {
  return api.get<Page<AdminShareVO>>(`${BASE}/shares`, { params });
}

/**
 * 强制取消分享
 */
export async function forceCancelShare(
  shareCode: string,
  reason?: string
): Promise<void> {
  return api.delete(`${BASE}/shares/${shareCode}`, { params: { reason } });
}

/**
 * 获取分享访问日志
 */
export async function getShareAccessLogs(
  shareCode: string,
  params?: PageParams
): Promise<Page<ShareAccessLogVO>> {
  return api.get<Page<ShareAccessLogVO>>(`${BASE}/shares/${shareCode}/logs`, {
    params,
  });
}

/**
 * 获取分享访问统计
 */
export async function getShareAccessStats(
  shareCode: string
): Promise<ShareAccessStatsVO> {
  return api.get<ShareAccessStatsVO>(`${BASE}/shares/${shareCode}/stats`);
}
