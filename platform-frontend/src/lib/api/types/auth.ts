/**
 * 认证响应
 * @see AuthorizeVO.java
 */
export interface AuthorizeVO {
	username: string;
	role: string;
	token: string;
	expire: string; // ISO datetime
}

/**
 * 用户信息
 * @see AccountVO.java
 */
export interface AccountVO {
	id: string;
	username: string;
	nickname?: string;
	email?: string;
	phone?: string;
	avatar?: string;
	role: string;
	status: number;
	createTime: string;
}

/**
 * 登录请求
 */
export interface LoginRequest {
	username: string;
	password: string;
}

/**
 * 注册请求
 */
export interface RegisterRequest {
	username: string;
	password: string;
	nickname?: string;
	email?: string;
	phone?: string;
}

/**
 * 密码修改请求
 */
export interface ChangePasswordRequest {
	oldPassword: string;
	newPassword: string;
}

/**
 * 用户更新请求
 */
export interface UpdateUserRequest {
	nickname?: string;
	email?: string;
	phone?: string;
	avatar?: string;
}

/**
 * 确认重置验证码请求
 */
export interface ConfirmResetRequest {
	email: string;
	code: string;
}

/**
 * 重置密码请求
 */
export interface ResetPasswordRequest {
	email: string;
	code: string;
	password: string;
}

/**
 * SSE 短期令牌响应
 * @see SseTokenVO.java
 */
export interface SseTokenVO {
	sseToken: string;
	expiresIn: number; // 有效期（秒）
}
