import { goto } from "$app/navigation";
import { browser } from "$app/environment";
import { PUBLIC_API_BASE_URL, PUBLIC_TENANT_ID } from "$env/static/public";
import {
  type Result,
  ResultCode,
  UNAUTHORIZED_CODES,
  RETRYABLE_CODES,
} from "./types";

// ===== Configuration =====

// In development, always use relative path (for Vite proxy)
// In production, use PUBLIC_API_BASE_URL if available, otherwise relative
const API_BASE = import.meta.env.DEV
  ? "/record-platform/api/v1"
  : `${PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;
const MAX_RETRIES = 3;
const RETRY_DELAY_BASE = 1000; // 1 second

// ===== Token Management =====

export const TOKEN_KEY = "auth_token";
export const TOKEN_EXPIRE_KEY = "auth_token_expire";
export const REMEMBER_ME_KEY = "auth_remember_me";

/**
 * Get the appropriate storage based on "remember me" preference
 */
function _getStorage(): Storage | null {
  if (!browser) return null;
  // Check if user chose "remember me" - stored in localStorage
  const rememberMe = localStorage.getItem(REMEMBER_ME_KEY) === "true";
  return rememberMe ? localStorage : sessionStorage;
}

export function getToken(): string | null {
  if (!browser) return null;

  // Try localStorage first (remember me), then sessionStorage
  let token = localStorage.getItem(TOKEN_KEY);
  let expire = localStorage.getItem(TOKEN_EXPIRE_KEY);

  if (!token) {
    token = sessionStorage.getItem(TOKEN_KEY);
    expire = sessionStorage.getItem(TOKEN_EXPIRE_KEY);
  }

  if (!token || !expire) return null;

  // Check expiration
  if (new Date(expire) <= new Date()) {
    clearToken();
    return null;
  }

  return token;
}

export function setToken(
  token: string,
  expire: string,
  rememberMe: boolean = true,
): void {
  if (!browser) return;

  // Clear from both storages first
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TOKEN_EXPIRE_KEY);

  // Store in appropriate storage
  const storage = rememberMe ? localStorage : sessionStorage;
  storage.setItem(TOKEN_KEY, token);
  storage.setItem(TOKEN_EXPIRE_KEY, expire);

  // Remember the preference in localStorage (persists across sessions)
  localStorage.setItem(REMEMBER_ME_KEY, String(rememberMe));
}

export function clearToken(): void {
  if (!browser) return;
  // Clear from both storages
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_KEY);
  localStorage.removeItem(REMEMBER_ME_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TOKEN_EXPIRE_KEY);
}

/**
 * Check if "remember me" was previously selected
 */
export function wasRememberMeSelected(): boolean {
  if (!browser) return false;
  return localStorage.getItem(REMEMBER_ME_KEY) === "true";
}

// ===== Error Handling =====

export class ApiError extends Error {
  code: number;
  isUnauthorized: boolean;
  isRateLimited: boolean;

  constructor(code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.isUnauthorized = (UNAUTHORIZED_CODES as readonly number[]).includes(
      code,
    );
    this.isRateLimited = (RETRYABLE_CODES as readonly number[]).includes(code);
  }
}

// ===== Request Configuration =====

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type RequestParams = Record<string, any>;

export interface RequestConfig {
  headers?: Record<string, string>;
  params?: RequestParams;
  timeout?: number;
  skipAuth?: boolean;
  retries?: number;
}

// ===== Utility Functions =====

function buildUrl(path: string, params?: RequestConfig["params"]): string {
  // SSR-safe: use relative URL when window is not available
  const baseUrl = browser ? window.location.origin : "";
  const url = new URL(`${API_BASE}${path}`, baseUrl || "http://localhost");

  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        url.searchParams.append(key, String(value));
      }
    });
  }

  // Return relative URL for SSR compatibility
  return browser ? url.toString() : `${url.pathname}${url.search}`;
}

function buildHeaders(config?: RequestConfig): Headers {
  const headers = new Headers({
    "Content-Type": "application/json",
    ...(config?.headers || {}),
  });

  // 添加租户 ID 请求头（企业多租户隔离）
  if (PUBLIC_TENANT_ID) {
    headers.set("X-Tenant-ID", PUBLIC_TENANT_ID);
  }

  if (!config?.skipAuth) {
    const token = getToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }

  return headers;
}

async function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ===== Core Request Function =====

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  config?: RequestConfig,
): Promise<T> {
  const maxRetries = config?.retries ?? MAX_RETRIES;
  let lastError: Error | null = null;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const controller = new AbortController();
      const timeout = config?.timeout ?? 30000;
      const timeoutId = setTimeout(() => controller.abort(), timeout);

      const headers = buildHeaders(config);

      // Remove Content-Type for FormData (browser will set it with boundary)
      if (body instanceof FormData) {
        headers.delete("Content-Type");
      }
      // Handle URLSearchParams (form-urlencoded data)
      else if (body instanceof URLSearchParams) {
        headers.set("Content-Type", "application/x-www-form-urlencoded");
      }

      const response = await fetch(buildUrl(path, config?.params), {
        method,
        headers,
        body:
          body instanceof FormData || body instanceof URLSearchParams
            ? body
            : body
              ? JSON.stringify(body)
              : undefined,
        signal: controller.signal,
      });

      clearTimeout(timeoutId);

      // Check Content-Type before parsing JSON
      const contentType = response.headers.get("Content-Type") || "";
      if (!contentType.includes("application/json")) {
        throw new ApiError(
          ResultCode.PARSE_ERROR,
          `服务器响应格式错误 (${response.status})`,
        );
      }

      // Parse response
      let result: Result<T>;
      try {
        result = await response.json();
      } catch {
        throw new ApiError(ResultCode.PARSE_ERROR, "响应解析失败");
      }

      // Handle success
      if (result.code === ResultCode.SUCCESS) {
        return result.data;
      }

      // Handle errors
      const error = new ApiError(result.code, result.message);

      // Handle unauthorized - redirect to login
      if (error.isUnauthorized) {
        clearToken();
        if (browser) {
          await goto("/login", { replaceState: true });
        }
        throw error;
      }

      // Handle rate limiting - retry with exponential backoff
      if (error.isRateLimited && attempt < maxRetries) {
        const delay = RETRY_DELAY_BASE * Math.pow(2, attempt);
        console.warn(`Rate limited, retrying in ${delay}ms...`);
        await sleep(delay);
        lastError = error;
        continue;
      }

      throw error;
    } catch (err) {
      if (err instanceof ApiError) {
        throw err;
      }

      // Network error - retry
      if (err instanceof TypeError && attempt < maxRetries) {
        const delay = RETRY_DELAY_BASE * Math.pow(2, attempt);
        console.warn(`Network error, retrying in ${delay}ms...`);
        await sleep(delay);
        lastError = err as Error;
        continue;
      }

      // Abort error (timeout)
      if (err instanceof DOMException && err.name === "AbortError") {
        throw new ApiError(ResultCode.TIMEOUT_ERROR, "请求超时");
      }

      throw new ApiError(ResultCode.NETWORK_ERROR, "网络连接失败");
    }
  }

  throw lastError || new ApiError(ResultCode.UNKNOWN_ERROR, "未知错误");
}

// ===== API Client =====

export const api = {
  /**
   * GET 请求
   */
  get<T>(path: string, config?: RequestConfig): Promise<T> {
    return request<T>("GET", path, undefined, config);
  },

  /**
   * POST 请求
   */
  post<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
    return request<T>("POST", path, body, config);
  },

  /**
   * PUT 请求
   */
  put<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
    return request<T>("PUT", path, body, config);
  },

  /**
   * DELETE 请求
   */
  delete<T>(path: string, config?: RequestConfig): Promise<T> {
    return request<T>("DELETE", path, undefined, config);
  },

  /**
   * PATCH 请求
   */
  patch<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
    return request<T>("PATCH", path, body, config);
  },

  /**
   * 文件上传
   */
  upload<T>(
    path: string,
    formData: FormData,
    config?: RequestConfig,
  ): Promise<T> {
    return request<T>("POST", path, formData, config);
  },
};

export default api;
