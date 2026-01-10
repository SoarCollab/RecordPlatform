import { goto } from "$app/navigation";
import { browser } from "$app/environment";
import { env } from "$env/dynamic/public";
import {
  type Result,
  ResultCode,
  UNAUTHORIZED_CODES,
  RETRYABLE_CODES,
} from "./types";

// ===== Configuration =====

const DEFAULT_API_BASE = import.meta.env.DEV
  ? "/record-platform/api/v1"
  : `${env.PUBLIC_API_BASE_URL || "/record-platform"}/api/v1`;
const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_RETRY_DELAY_BASE = 1000;

// ===== Token Management =====

export const TOKEN_KEY = "auth_token";
export const TOKEN_EXPIRE_KEY = "auth_token_expire";
export const REMEMBER_ME_KEY = "auth_remember_me";

function _getStorage(): Storage | null {
  if (!browser) return null;
  const rememberMe = localStorage.getItem(REMEMBER_ME_KEY) === "true";
  return rememberMe ? localStorage : sessionStorage;
}

export function getToken(): string | null {
  if (!browser) return null;

  let token = localStorage.getItem(TOKEN_KEY);
  let expire = localStorage.getItem(TOKEN_EXPIRE_KEY);

  if (!token) {
    token = sessionStorage.getItem(TOKEN_KEY);
    expire = sessionStorage.getItem(TOKEN_EXPIRE_KEY);
  }

  if (!token || !expire) return null;

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

  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TOKEN_EXPIRE_KEY);

  const storage = rememberMe ? localStorage : sessionStorage;
  storage.setItem(TOKEN_KEY, token);
  storage.setItem(TOKEN_EXPIRE_KEY, expire);

  localStorage.setItem(REMEMBER_ME_KEY, String(rememberMe));
}

export function clearToken(): void {
  if (!browser) return;
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRE_KEY);
  localStorage.removeItem(REMEMBER_ME_KEY);
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(TOKEN_EXPIRE_KEY);
}

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

// ===== Factory Configuration =====

export interface ApiClientConfig {
  baseUrl?: string;
  tenantId?: string;
  maxRetries?: number;
  retryDelayBase?: number;
  fetch?: typeof fetch;
  getToken?: () => string | null;
  onUnauthorized?: () => Promise<void> | void;
}

// ===== Utility Functions =====

function buildUrl(
  baseUrl: string,
  path: string,
  params?: RequestConfig["params"],
): string {
  const baseUrlForParsing = browser
    ? window.location.origin
    : "http://localhost";
  const url = new URL(`${baseUrl}${path}`, baseUrlForParsing);

  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        url.searchParams.append(key, String(value));
      }
    });
  }

  return browser ? url.toString() : `${url.pathname}${url.search}`;
}

function buildHeaders(
  config: RequestConfig | undefined,
  tenantId: string | undefined,
  tokenGetter: () => string | null,
): Headers {
  const headers = new Headers({
    "Content-Type": "application/json",
    ...(config?.headers || {}),
  });

  if (tenantId) {
    headers.set("X-Tenant-ID", tenantId);
  }

  if (!config?.skipAuth) {
    const token = tokenGetter();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }

  return headers;
}

async function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ===== API Client Factory =====

export function createApiClient(clientConfig: ApiClientConfig = {}) {
  const {
    baseUrl = DEFAULT_API_BASE,
    tenantId = env.PUBLIC_TENANT_ID || "0",
    maxRetries = DEFAULT_MAX_RETRIES,
    retryDelayBase = DEFAULT_RETRY_DELAY_BASE,
    fetch: fetchFn = globalThis.fetch,
    getToken: tokenGetter = getToken,
    onUnauthorized = async () => {
      clearToken();
      if (browser) {
        await goto("/login", { replaceState: true });
      }
    },
  } = clientConfig;

  async function request<T>(
    method: string,
    path: string,
    body?: unknown,
    config?: RequestConfig,
  ): Promise<T> {
    const requestMaxRetries = config?.retries ?? maxRetries;
    let lastError: Error | null = null;

    for (let attempt = 0; attempt <= requestMaxRetries; attempt++) {
      try {
        const controller = new AbortController();
        const timeout = config?.timeout ?? 30000;
        const timeoutId = setTimeout(() => controller.abort(), timeout);

        const headers = buildHeaders(config, tenantId, tokenGetter);

        if (body instanceof FormData) {
          headers.delete("Content-Type");
        } else if (body instanceof URLSearchParams) {
          headers.set("Content-Type", "application/x-www-form-urlencoded");
        }

        const response = await fetchFn(
          buildUrl(baseUrl, path, config?.params),
          {
            method,
            headers,
            body:
              body instanceof FormData || body instanceof URLSearchParams
                ? body
                : body
                  ? JSON.stringify(body)
                  : undefined,
            signal: controller.signal,
          },
        );

        clearTimeout(timeoutId);

        const contentType = response.headers.get("Content-Type") || "";
        if (!contentType.includes("application/json")) {
          throw new ApiError(
            ResultCode.PARSE_ERROR,
            `服务器响应格式错误 (${response.status})`,
          );
        }

        let result: Result<T>;
        try {
          result = await response.json();
        } catch {
          throw new ApiError(ResultCode.PARSE_ERROR, "响应解析失败");
        }

        if (result.code === ResultCode.SUCCESS) {
          return result.data;
        }

        const error = new ApiError(result.code, result.message);

        if (error.isUnauthorized) {
          await onUnauthorized();
          throw error;
        }

        if (error.isRateLimited && attempt < requestMaxRetries) {
          const delay = retryDelayBase * Math.pow(2, attempt);
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

        if (err instanceof TypeError && attempt < requestMaxRetries) {
          const delay = retryDelayBase * Math.pow(2, attempt);
          console.warn(`Network error, retrying in ${delay}ms...`);
          await sleep(delay);
          lastError = err as Error;
          continue;
        }

        if (err instanceof DOMException && err.name === "AbortError") {
          throw new ApiError(ResultCode.TIMEOUT_ERROR, "请求超时");
        }

        throw new ApiError(ResultCode.NETWORK_ERROR, "网络连接失败");
      }
    }

    throw lastError || new ApiError(ResultCode.UNKNOWN_ERROR, "未知错误");
  }

  return {
    get<T>(path: string, config?: RequestConfig): Promise<T> {
      return request<T>("GET", path, undefined, config);
    },

    post<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
      return request<T>("POST", path, body, config);
    },

    put<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
      return request<T>("PUT", path, body, config);
    },

    delete<T>(path: string, config?: RequestConfig): Promise<T> {
      return request<T>("DELETE", path, undefined, config);
    },

    patch<T>(path: string, body?: unknown, config?: RequestConfig): Promise<T> {
      return request<T>("PATCH", path, body, config);
    },

    upload<T>(
      path: string,
      formData: FormData,
      config?: RequestConfig,
    ): Promise<T> {
      return request<T>("POST", path, formData, config);
    },
  };
}

// ===== Default API Client Instance =====

export const api = createApiClient();

export default api;
