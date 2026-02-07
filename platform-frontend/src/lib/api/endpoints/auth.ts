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
 * 规范化后端返回的用户对象：将 `externalId` 兼容映射为前端通用的 `id`。
 *
 * @param account 后端返回的用户信息
 * @returns 统一包含 `id` 的用户信息
 */
function normalizeAccountVO(account: AccountVO): AccountVO {
  const anyAccount = account as AccountVO & {
    externalId?: unknown;
    id?: unknown;
  };
  const idFromId =
    typeof anyAccount.id === "string" ? anyAccount.id : undefined;
  const idFromExternalId =
    typeof anyAccount.externalId === "string"
      ? anyAccount.externalId
      : undefined;

  return {
    ...account,
    id: idFromId || idFromExternalId || "",
    externalId: idFromExternalId,
  };
}

/**
 * 用户登录。
 *
 * @param data 登录凭证
 * @param rememberMe 是否记住登录状态
 * @returns 鉴权结果
 */
export async function login(
  data: LoginRequest,
  rememberMe: boolean = true,
): Promise<AuthorizeVO> {
  const result = await api.post<AuthorizeVO>(`${BASE}/login`, data, {
    skipAuth: true,
  });
  setToken(result.token, result.expire, rememberMe);
  return result;
}

/**
 * 用户注册。
 *
 * @param data 注册信息
 */
export async function register(data: RegisterRequest): Promise<void> {
  await api.post<string>(`${BASE}/register`, data, {
    skipAuth: true,
  });
}

/**
 * 用户登出。
 */
export async function logout(): Promise<void> {
  try {
    await api.post(`${BASE}/logout`);
  } finally {
    clearToken();
  }
}

/**
 * 获取当前用户信息。
 *
 * @returns 当前用户
 */
export async function getCurrentUser(): Promise<AccountVO> {
  const account = await api.get<AccountVO>("/users/info");
  return normalizeAccountVO(account);
}

/**
 * 修改密码。
 *
 * @param data 修改参数
 */
export async function changePassword(
  data: ChangePasswordRequest,
): Promise<void> {
  await api.put("/users/password", data);
}

/**
 * 更新用户信息。
 *
 * @param data 更新参数
 * @returns 更新后的用户信息
 */
export async function updateUser(data: UpdateUserRequest): Promise<AccountVO> {
  const account = await api.put<AccountVO>("/users/info", data);
  return normalizeAccountVO(account);
}

/**
 * 刷新 Token。
 *
 * @returns 刷新结果
 */
export async function refreshToken(): Promise<RefreshTokenVO> {
  const result = await api.post<RefreshTokenVO>(`${BASE}/tokens/refresh`);
  setToken(result.token, result.expire);
  return result;
}

/**
 * 发送注册验证码。
 *
 * @param email 注册邮箱
 */
export async function sendRegisterCode(email: string): Promise<void> {
  await api.post(`${BASE}/verification-codes`, null, {
    params: { email, type: "register" },
    skipAuth: true,
  });
}

/**
 * 发送重置密码验证码。
 *
 * @param email 注册邮箱
 */
export async function sendResetCode(email: string): Promise<void> {
  await api.post(`${BASE}/verification-codes`, null, {
    params: { email, type: "reset" },
    skipAuth: true,
  });
}

/**
 * 确认重置验证码。
 *
 * @param data 验证参数
 */
export async function confirmResetCode(
  data: ConfirmResetRequest,
): Promise<void> {
  await api.post(`${BASE}/password-resets/confirm`, data, { skipAuth: true });
}

/**
 * 重置密码。
 *
 * @param data 重置参数
 */
export async function resetPassword(data: ResetPasswordRequest): Promise<void> {
  await api.put(`${BASE}/password-resets`, data, { skipAuth: true });
}

/**
 * 获取 SSE 短期令牌。
 *
 * @returns SSE token
 */
export async function getSseToken(): Promise<SseTokenVO> {
  return api.post<SseTokenVO>(`${BASE}/tokens/sse`);
}
