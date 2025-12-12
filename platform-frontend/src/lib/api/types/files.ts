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
}

/**
 * 文件状态枚举
 */
export enum FileStatus {
	UPLOADING = 0,
	ENCRYPTING = 1,
	STORING = 2,
	CERTIFYING = 3,
	COMPLETED = 4,
	FAILED = -1
}

/**
 * 文件状态标签映射
 */
export const FileStatusLabel: Record<FileStatus, string> = {
	[FileStatus.UPLOADING]: '上传中',
	[FileStatus.ENCRYPTING]: '加密中',
	[FileStatus.STORING]: '存储中',
	[FileStatus.CERTIFYING]: '存证中',
	[FileStatus.COMPLETED]: '已完成',
	[FileStatus.FAILED]: '失败'
};

/**
 * 开始上传响应
 * @see StartUploadVO.java
 */
export interface StartUploadVO {
	clientId: string;
	chunkSize: number;
	totalChunks: number;
	processedChunks: number[]; // 已上传分片索引 (断点续传)
	resumed: boolean;
}

/**
 * 开始上传请求
 */
export interface StartUploadRequest {
	fileName: string;
	fileSize: number;
	contentType: string;
	chunkSize?: number;
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
	totalChunks: number;
	uploadedChunks: number;
	processedChunks: number[];
	progress: number; // 0-100
	status: string;
}

/**
 * 分享信息
 * @see SharingVO.java
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
	fileId: string;
	expireHours?: number;
	maxDownloads?: number;
	password?: string;
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
