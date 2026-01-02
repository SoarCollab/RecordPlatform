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
  email?: string;
  avatar?: string;
  role: string;
  registerTime: string;
  nickname?: string;
  /** 软删除标记：0-正常，1-已禁用 */
  deleted?: number;
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
 * @see EmailRegisterVO.java
 */
export interface RegisterRequest {
  username: string;
  password: string;
  email: string; // Required for email verification
  code: string; // Email verification code
  nickname?: string;
}

/**
 * 密码修改请求
 * @see ChangePasswordVO.java
 */
export interface ChangePasswordRequest {
  password: string; // 旧密码
  new_password: string; // 新密码
}

/**
 * 用户更新请求
 * @see UpdateUserVO.java
 */
export interface UpdateUserRequest {
  avatar?: string;
  nickname?: string;
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

/**
 * Token 刷新响应
 * @see RefreshTokenVO.java
 */
export interface RefreshTokenVO {
  token: string;
  expire: string; // ISO datetime
}
