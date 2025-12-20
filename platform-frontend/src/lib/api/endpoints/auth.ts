import { api, setToken, clearToken } from "../client";
import type {
  AuthorizeVO,
  AccountVO,
  LoginRequest,
  RegisterRequest,
  ChangePasswordRequest,
  UpdateUserRequest,
  ConfirmResetRequest,
  ResetPasswordRequest,
  SseTokenVO,
  RefreshTokenVO,
} from "../types";

const BASE = "/auth";

/**
 * 用户登录
 * @param data 登录凭证
 * @param rememberMe 是否记住登录状态
 */
export async function login(
  data: LoginRequest,
  rememberMe: boolean = true
): Promise<AuthorizeVO> {
  const result = await api.post<AuthorizeVO>(`${BASE}/login`, data, {
    skipAuth: true,
  });
  setToken(result.token, result.expire, rememberMe);
  return result;
}

/**
 * 用户注册
 * @param data 注册信息
 * @note Backend returns Result<String>, not AuthorizeVO. Use login() after registration to get token.
 */
export async function register(data: RegisterRequest): Promise<void> {
  await api.post<string>(`${BASE}/register`, data, {
    skipAuth: true,
  });
  // Registration only creates account, doesn't return token
}

/**
 * 用户登出
 */
export async function logout(): Promise<void> {
  try {
    await api.post(`${BASE}/logout`);
  } finally {
    clearToken();
  }
}

/**
 * 获取当前用户信息
 */
export async function getCurrentUser(): Promise<AccountVO> {
  return api.get<AccountVO>("/users/info");
}

/**
 * 修改密码
 * @see AccountController.changePassword
 * @note 字段名与后端 ChangePasswordVO 对齐: password, new_password
 */
export async function changePassword(
  data: ChangePasswordRequest
): Promise<void> {
  return api.post("/users/change-password", data);
}

/**
 * 更新用户信息
 * @see AccountController.update
 * @see UpdateUserVO.java
 */
export async function updateUser(data: UpdateUserRequest): Promise<AccountVO> {
  return api.put<AccountVO>("/users/info", data);
}

/**
 * 刷新 Token
 * @see AuthorizeController.refresh
 * @see RefreshTokenVO.java
 */
export async function refreshToken(): Promise<RefreshTokenVO> {
  const result = await api.post<RefreshTokenVO>(`${BASE}/refresh`);
  setToken(result.token, result.expire);
  return result;
}

/**
 * 发送注册验证码
 */
export async function sendRegisterCode(email: string): Promise<void> {
  await api.get(`${BASE}/ask-code`, {
    params: { email, type: "register" },
    skipAuth: true,
  });
}

/**
 * 发送重置密码验证码
 */
export async function sendResetCode(email: string): Promise<void> {
  await api.get(`${BASE}/ask-code`, {
    params: { email, type: "reset" },
    skipAuth: true,
  });
}

/**
 * 确认重置验证码
 */
export async function confirmResetCode(
  data: ConfirmResetRequest
): Promise<void> {
  await api.post(`${BASE}/reset-confirm`, data, { skipAuth: true });
}

/**
 * 重置密码
 */
export async function resetPassword(data: ResetPasswordRequest): Promise<void> {
  await api.post(`${BASE}/reset-password`, data, { skipAuth: true });
}

/**
 * 获取 SSE 短期令牌
 * 用于建立 SSE 连接，有效期 30 秒，一次性使用
 */
export async function getSseToken(): Promise<SseTokenVO> {
  return api.post<SseTokenVO>(`${BASE}/sse-token`);
}
