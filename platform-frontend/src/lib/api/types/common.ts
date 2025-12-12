// ===== Common Types =====

/**
 * 后端统一响应格式
 * @see ResultEnum.java
 */
export interface Result<T> {
	code: number;
	message: string;
	data: T;
}

/**
 * 分页响应格式
 * @see MyBatis Plus IPage
 */
export interface Page<T> {
	records: T[];
	total: number;
	size: number;
	current: number;
	pages: number;
}

/**
 * 分页请求参数
 */
export interface PageParams {
	current?: number;
	size?: number;
}

/**
 * 错误码枚举 (对应 ResultEnum.java)
 */
export const ResultCode = {
	// 成功
	SUCCESS: 200,

	// 参数错误 10000-19999
	PARAM_ERROR: 10001,
	PARAM_NULL: 10002,
	PARAM_FORMAT_ERROR: 10003,
	PARAM_TYPE_ERROR: 10004,
	DATA_EXIST: 10005,

	// 用户/认证错误 20000-29999
	USER_NOT_EXIST: 20001,
	USER_ALREADY_EXISTS: 20002,
	USER_PASSWORD_ERROR: 20003,
	USER_DISABLED: 20004,
	USER_LOCKED: 20005,
	PASSWORD_INCORRECT: 20006,
	ORIGINAL_PASSWORD_EMPTY: 20007,
	ORIGINAL_PASSWORD_WRONG: 20008,

	// 外部服务错误 30000-39999
	FILE_UPLOAD_ERROR: 30001,
	FILE_DOWNLOAD_ERROR: 30002,
	FILE_ENCRYPT_ERROR: 30003,
	FILE_DECRYPT_ERROR: 30004,
	MINIO_ERROR: 30005,
	BLOCKCHAIN_ERROR: 30006,

	// 系统错误 40000-49999
	SYSTEM_ERROR: 40001,
	NETWORK_ERROR: 40002,
	TIMEOUT_ERROR: 40003,
	RATE_LIMIT_ERROR: 40004,
	UNKNOWN_ERROR: 40099,

	// 数据错误 50000-59999
	DATA_NOT_FOUND: 50001,
	DATA_OPERATION_ERROR: 50002,
	DATA_INTEGRITY_ERROR: 50003,
	FILE_NOT_EXIST: 50004,
	FILE_ALREADY_DELETED: 50005,
	FILE_UPLOAD_INCOMPLETE: 50006,

	// 消息服务错误 60000-69999
	MESSAGE_SEND_ERROR: 60001,
	CONVERSATION_NOT_FOUND: 60002,
	SELF_MESSAGE_ERROR: 60003,

	// 认证/权限错误 70000-79999
	AUTH_INVALID: 70001,
	PERMISSION_DENIED: 70002,
	OPERATION_DENIED: 70003,
	TOKEN_EXPIRED: 70004,
	IP_RATE_LIMIT: 70005,
	TOKEN_INVALID: 70006,
	INSUFFICIENT_PERMISSIONS: 70007
} as const;

export type ResultCodeType = (typeof ResultCode)[keyof typeof ResultCode];

/**
 * 需要清除 Token 并跳转登录的错误码
 */
export const UNAUTHORIZED_CODES = [
	ResultCode.AUTH_INVALID,
	ResultCode.TOKEN_EXPIRED,
	ResultCode.TOKEN_INVALID
];

/**
 * 可重试的错误码
 */
export const RETRYABLE_CODES = [ResultCode.RATE_LIMIT_ERROR, ResultCode.IP_RATE_LIMIT];
