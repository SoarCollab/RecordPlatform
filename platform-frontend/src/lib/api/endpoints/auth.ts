import {
  api,
  ApiError,
  createApiClient,
  setToken,
  clearToken,
  beginCredentialChange,
  getCredentialSnapshot,
  isCurrentCredential,
} from "../client";
import { ResultCode } from "../types/common";
import type {
  AuthScope,
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
const platformAuth = createApiClient({ tenantId: "0" });

/** Select the immutable authentication context, never a caller-supplied tenant. */
function scopedTransport(scope: AuthScope | null) {
  return scope === "platform" ? platformAuth : api;
}

/** Reject malformed credential responses before writing browser persistence. */
function validateCredentials(result: RefreshTokenVO): void {
  if (
    !result ||
    typeof result.token !== "string" ||
    !result.token.trim() ||
    typeof result.expire !== "string" ||
    !Number.isFinite(Date.parse(result.expire)) ||
    Date.parse(result.expire) <= Date.now()
  ) {
    throw new ApiError(ResultCode.PARSE_ERROR, "认证响应格式无效");
  }
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
  mode: AuthScope = "tenant",
): Promise<AuthorizeVO> {
  const snapshot = beginCredentialChange();
  const result = await scopedTransport(mode).post<AuthorizeVO>(
    `${BASE}/login`,
    data,
    {
      skipAuth: true,
      retries: 0,
    },
  );
  if (!isCurrentCredential(snapshot)) {
    throw new DOMException("The session has changed", "AbortError");
  }
  validateCredentials(result);
  if (
    result.scope !== mode ||
    (mode === "platform"
      ? result.role !== "platform_admin"
      : !["user", "admin", "monitor"].includes(result.role))
  ) {
    throw new ApiError(
      ResultCode.PERMISSION_UNAUTHORIZED,
      "请使用与账号身份对应的登录入口",
    );
  }
  setToken(result.token, result.expire, rememberMe, result.scope);
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
  const snapshot = beginCredentialChange();
  try {
    await scopedTransport(snapshot.scope).post(`${BASE}/logout`, undefined, {
      retries: 0,
    });
  } finally {
    if (isCurrentCredential(snapshot)) clearToken();
  }
}

/**
 * 获取当前用户信息。
 *
 * @returns 当前用户
 */
export async function getCurrentUser(): Promise<AccountVO> {
  return api.get<AccountVO>("/users/info");
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
  return api.put<AccountVO>("/users/info", data);
}

/**
 * 刷新 Token。
 *
 * @returns 刷新结果
 */
export async function refreshToken(): Promise<RefreshTokenVO> {
  const snapshot = getCredentialSnapshot();
  const result = await scopedTransport(snapshot.scope).post<RefreshTokenVO>(
    `${BASE}/tokens/refresh`,
    undefined,
    { retries: 0 },
  );
  if (!isCurrentCredential(snapshot)) {
    throw new DOMException("The session has changed", "AbortError");
  }
  validateCredentials(result);
  setToken(result.token, result.expire, snapshot.rememberMe, snapshot.scope);
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
