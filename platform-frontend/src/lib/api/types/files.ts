/**
 * 文件信息
 * @see FileVO.java
 */
export interface FileVO {
  id: string;
  fileName: string;
  fileHash: string;
  fileSize: number;
  contentType: string;
  transactionHash?: string;
  blockNumber?: number;
  status: FileStatus;
  createTime: string;
  updateTime?: string;
  /** 原上传者用户名（来自分享保存的文件） */
  originOwnerName?: string;
  /** 直接分享者用户名 */
  sharedFromUserName?: string;
}

/**
 * 用户文件统计信息
 * @see UserFileStatsVO.java
 */
export interface UserFileStatsVO {
  /** 文件总数 */
  totalFiles: number;
  /** 存储用量（字节） */
  totalStorage: number;
  /** 分享文件数 */
  sharedFiles: number;
  /** 今日上传数 */
  todayUploads: number;
}

/**
 * 文件状态枚举（与后端 FileUploadStatus 对齐）
 * @see FileUploadStatus.java
 *
 * 注意：这是文件的最终持久化状态，不是上传进度状态。
 * 上传进度状态请使用 UploadProgressStatus 枚举。
 */
export enum FileStatus {
  /** 已删除 */
  DELETED = 2,
  /** 上传成功/已完成 */
  COMPLETED = 1,
  /** 待上传/处理中 */
  PROCESSING = 0,
  /** 上传失败 */
  FAILED = -1,
  /** 无操作（系统内部状态） */
  NOOP = -2,
}

/**
 * 文件状态标签映射（持久化状态）
 */
export const FileStatusLabel: Record<FileStatus, string> = {
  [FileStatus.DELETED]: "已删除",
  [FileStatus.COMPLETED]: "已完成",
  [FileStatus.PROCESSING]: "处理中",
  [FileStatus.FAILED]: "失败",
  [FileStatus.NOOP]: "-",
};

/**
 * 上传进度状态枚举（前端本地状态，用于展示上传进度）
 * 这是上传过程中的细粒度状态，与后端无关。
 */
export enum UploadProgressStatus {
  /** 等待上传 */
  PENDING = "pending",
  /** 上传中 */
  UPLOADING = "uploading",
  /** 加密中 */
  ENCRYPTING = "encrypting",
  /** 存储中 */
  STORING = "storing",
  /** 存证中 */
  CERTIFYING = "certifying",
  /** 上传完成 */
  COMPLETED = "completed",
  /** 上传失败 */
  FAILED = "failed",
  /** 已暂停 */
  PAUSED = "paused",
}

/**
 * 上传进度状态标签映射
 */
export const UploadProgressStatusLabel: Record<UploadProgressStatus, string> = {
  [UploadProgressStatus.PENDING]: "等待上传",
  [UploadProgressStatus.UPLOADING]: "上传中",
  [UploadProgressStatus.ENCRYPTING]: "加密中",
  [UploadProgressStatus.STORING]: "存储中",
  [UploadProgressStatus.CERTIFYING]: "存证中",
  [UploadProgressStatus.COMPLETED]: "已完成",
  [UploadProgressStatus.FAILED]: "失败",
  [UploadProgressStatus.PAUSED]: "已暂停",
};

/**
 * 开始上传响应
 * @see StartUploadVO.java
 */
export interface StartUploadVO {
  clientId: string;
  chunkSize: number;
  totalChunks: number;
  singleChunk: boolean; // 是否为单分片文件
  processedChunks: number[]; // 已上传分片索引 (断点续传)
  resumed: boolean;
}

/**
 * 恢复上传响应
 * @see ResumeUploadVO.java
 */
export interface ResumeUploadVO {
  processedChunks: number[];
  totalChunks: number;
}

/**
 * 开始上传请求
 */
export interface StartUploadRequest {
  fileName: string;
  fileSize: number;
  contentType: string;
  chunkSize: number;
  totalChunks: number;
  fileHash?: string;
}

/**
 * 上传分片请求
 */
export interface UploadChunkRequest {
  clientId: string;
  chunkNumber: number;
  file: Blob;
}

/**
 * 上传进度响应
 * @see ProgressVO.java
 */
export interface ProgressVO {
  clientId: string;
  progress: number; // 总体进度百分比
  uploadProgress: number; // 原始分片上传进度百分比
  processProgress: number; // 分片处理进度百分比
  uploadedChunkCount: number; // 已上传原始分片数量
  processedChunkCount: number; // 已处理分片数量
  totalChunks: number; // 总分片数量
  status: string; // 上传状态：pending/uploading/processing/paused/completed
}

/**
 * 文件上传状态响应 (checkUploadStatus 接口)
 * @see FileUploadStatusVO.java
 */
export interface FileUploadStatusVO {
  /** 文件名 */
  fileName: string;
  /** 文件大小 */
  fileSize: number;
  /** 客户端ID */
  clientId: string;
  /** 是否暂停 */
  paused: boolean;
  /** 上传状态：UPLOADING -> 上传中, PAUSED -> 暂停, PROCESSING_COMPLETE -> 处理完成 */
  status: string;
  /** 上传进度百分比 */
  progress: number;
  /** 已处理的分片序号列表 */
  processedChunks: number[];
  /** 已处理分片数量 */
  processedChunkCount: number;
  /** 总分片数量 */
  totalChunks: number;
}

/**
 * 分享记录信息 (我的分享列表)
 * @see FileShareVO.java
 * @note 用于 getMyShares 接口返回
 */
export interface FileShareVO {
  id: string;
  sharingCode: string;
  fileHashes: string[];
  fileNames: string[];
  maxAccesses?: number;
  accessCount: number;
  hasPassword: boolean;
  expireTime?: string;
  status: number; // 0-已取消, 1-有效, 2-已过期
  statusDesc?: string;
  isValid: boolean;
  shareType: ShareType;
  shareTypeDesc: string;
  createTime: string;
}

/**
 * 分享类型枚举
 * @see ShareType.java
 */
export enum ShareType {
  /** 公开分享（无需登录即可下载） */
  PUBLIC = 0,
  /** 私密分享（需要登录才能下载） */
  PRIVATE = 1,
}

/**
 * 分享类型标签映射
 */
export const ShareTypeLabel: Record<ShareType, string> = {
  [ShareType.PUBLIC]: "公开分享",
  [ShareType.PRIVATE]: "私密分享",
};

/**
 * 分享类型描述映射
 */
export const ShareTypeDesc: Record<ShareType, string> = {
  [ShareType.PUBLIC]: "无需登录即可下载",
  [ShareType.PRIVATE]: "需要登录才能下载",
};

/**
 * 分享信息 (创建分享响应)
 * @deprecated 后端 POST /files/share 仅返回分享码字符串
 * @note 保留此类型用于兼容，但实际 createShare 返回 string
 */
export interface SharingVO {
  id: string;
  shareCode: string;
  fileId: string;
  fileName: string;
  expireTime: string;
  maxDownloads?: number;
  downloadCount: number;
  createTime: string;
}

/**
 * 创建分享请求
 */
export interface CreateShareRequest {
  fileHash: string[];
  expireMinutes: number;
  /** 分享类型：0-公开，1-私密 */
  shareType?: ShareType;
}

/**
 * 更新分享请求
 * @see UpdateShareVO.java
 */
export interface UpdateShareRequest {
  shareCode: string;
  /** 分享类型：0-公开，1-私密 */
  shareType?: ShareType;
  /** 延长有效期（分钟） */
  extendMinutes?: number;
}

/**
 * 文件查询参数
 */
export interface FileQueryParams {
  keyword?: string;
  status?: FileStatus;
  startTime?: string;
  endTime?: string;
}

/**
 * 区块链交易信息
 * @see TransactionVO.java
 */
export interface TransactionVO {
  transactionHash: string;
  blockNumber: number;
  timestamp: string;
}

/**
 * 分享的文件信息 (对应 API getSharingFiles 返回)
 */
export interface SharedFileVO {
  id: string;
  fileName: string;
  fileSize: number;
  fileHash: string;
  contentType: string;
  ownerName: string;
  createTime: string;
}

/**
 * 保存分享文件请求
 */
export interface SaveShareFileRequest {
  sharingFileIdList: string[];
  /** 分享码（用于链路追踪，必填） */
  shareCode: string;
}

/**
 * 文件解密信息
 * @see FileDecryptInfoVO.java
 */
export interface FileDecryptInfoVO {
  /** 初始密钥（最后一个分片的解密密钥，Base64编码） */
  initialKey: string;
  /** 文件名 */
  fileName: string;
  /** 文件大小（字节） */
  fileSize: number;
  /** 文件MIME类型 */
  contentType: string;
  /** 分片数量 */
  chunkCount: number;
  /** 文件哈希 */
  fileHash: string;
}

/**
 * 分享访问日志
 * @see ShareAccessLogVO.java
 */
export interface ShareAccessLogVO {
  id: string;
  shareCode: string;
  /** 操作类型：1=查看，2=下载，3=保存 */
  actionType: number;
  actionTypeDesc: string;
  actorUserId?: string;
  actorUserName: string;
  actorIp: string;
  fileHash?: string;
  fileName?: string;
  accessTime: string;
}

/**
 * 分享访问统计
 * @see ShareAccessStatsVO.java
 */
export interface ShareAccessStatsVO {
  shareCode: string;
  viewCount: number;
  downloadCount: number;
  saveCount: number;
  uniqueActors: number;
  totalAccess: number;
}

/**
 * 文件溯源信息
 * @see FileProvenanceVO.java
 */
export interface FileProvenanceVO {
  fileId: string;
  fileHash: string;
  fileName: string;
  /** 是否为原始文件（自己上传的） */
  isOriginal: boolean;
  originUserId?: string;
  originUserName?: string;
  sharedFromUserId?: string;
  sharedFromUserName?: string;
  /** 分享链路深度（0=原始文件，1=一次分享，2=二次分享...） */
  depth: number;
  saveTime?: string;
  shareCode?: string;
  /** 完整分享链路 */
  chain: ProvenanceNode[];
}

/**
 * 分享链路节点
 */
export interface ProvenanceNode {
  userId: string;
  userName: string;
  fileId: string;
  depth: number;
  shareCode?: string;
  time?: string;
}
