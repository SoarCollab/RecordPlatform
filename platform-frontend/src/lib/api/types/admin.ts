/**
 * 管理员相关类型定义
 * @see AdminFileVO.java, AdminShareVO.java, etc.
 */

import type { ProvenanceNode, ShareAccessLogVO } from "./files";

/**
 * 管理员文件列表 VO
 */
export interface AdminFileVO {
  id: string;
  fileName: string;
  fileHash: string;
  fileSize: number;
  contentType: string;
  status: number;
  statusDesc: string;
  ownerId: string;
  ownerName: string;
  originOwnerId?: string;
  originOwnerName?: string;
  sharedFromUserId?: string;
  sharedFromUserName?: string;
  isOriginal: boolean;
  depth: number;
  transactionHash?: string;
  blockNumber?: number;
  refCount?: number;
  createTime: string;
  updateTime?: string;
}

/**
 * 相关分享信息
 */
export interface RelatedShare {
  shareCode: string;
  sharerName: string;
  shareType: number;
  status: number;
  createTime: string;
  expireTime?: string;
  accessCount: number;
}

/**
 * 管理员文件详情 VO
 */
export interface AdminFileDetailVO {
  id: string;
  fileName: string;
  fileHash: string;
  fileSize: number;
  contentType: string;
  status: number;
  statusDesc: string;
  createTime: string;
  updateTime?: string;
  ownerId: string;
  ownerName: string;
  originOwnerId?: string;
  originOwnerName?: string;
  sharedFromUserId?: string;
  sharedFromUserName?: string;
  isOriginal: boolean;
  depth: number;
  saveShareCode?: string;
  provenanceChain: ProvenanceNode[];
  transactionHash?: string;
  blockNumber?: number;
  refCount: number;
  relatedShares: RelatedShare[];
  recentAccessLogs: ShareAccessLogVO[];
}

/**
 * 管理员分享列表 VO
 */
export interface AdminShareVO {
  id: string;
  shareCode: string;
  sharerId: string;
  sharerName: string;
  shareType: number;
  shareTypeDesc: string;
  status: number;
  statusDesc: string;
  fileCount: number;
  fileHashes: string[];
  fileNames: string[];
  accessCount: number;
  maxAccess?: number;
  hasPassword: boolean;
  createTime: string;
  expireTime?: string;
  viewCount: number;
  downloadCount: number;
  saveCount: number;
  uniqueActors: number;
}

/**
 * 管理员文件查询参数
 */
export interface AdminFileQueryParams {
  keyword?: string;
  status?: number;
  ownerId?: string;
  ownerName?: string;
  originalOnly?: boolean;
  sharedOnly?: boolean;
  startTime?: string;
  endTime?: string;
}

/**
 * 管理员分享查询参数
 */
export interface AdminShareQueryParams {
  keyword?: string;
  status?: number;
  shareType?: number;
  sharerId?: string;
  sharerName?: string;
  startTime?: string;
  endTime?: string;
}

/**
 * 更新文件状态请求
 */
export interface UpdateFileStatusRequest {
  status: number;
  reason?: string;
}

// 文件状态枚举（管理员视图）
export const AdminFileStatus = {
  PROCESSING: 0,
  COMPLETED: 1,
  DELETED: 2,
  FAILED: -1,
} as const;

export const AdminFileStatusLabel: Record<number, string> = {
  [AdminFileStatus.PROCESSING]: "处理中",
  [AdminFileStatus.COMPLETED]: "已完成",
  [AdminFileStatus.DELETED]: "已删除",
  [AdminFileStatus.FAILED]: "失败",
};

// 分享状态枚举（管理员视图）
export const AdminShareStatus = {
  CANCELLED: 0,
  ACTIVE: 1,
  EXPIRED: 2,
} as const;

export const AdminShareStatusLabel: Record<number, string> = {
  [AdminShareStatus.CANCELLED]: "已取消",
  [AdminShareStatus.ACTIVE]: "有效",
  [AdminShareStatus.EXPIRED]: "已过期",
};
