import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ResultCode } from "./types";
import {
  getToken,
  setToken,
  clearToken,
  wasRememberMeSelected,
  ApiError,
  createApiClient,
  TOKEN_KEY,
  TOKEN_EXPIRE_KEY,
  REMEMBER_ME_KEY,
} from "./client";

describe("API Client", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();

    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("Token Management", () => {
    describe("setToken", () => {
      it("should store token in localStorage when rememberMe is true", () => {
        const futureDate = new Date(Date.now() + 3600000).toISOString();
        setToken("test-token", futureDate, true);

        expect(localStorage.getItem(TOKEN_KEY)).toBe("test-token");
        expect(localStorage.getItem(TOKEN_EXPIRE_KEY)).toBe(futureDate);
        expect(localStorage.getItem(REMEMBER_ME_KEY)).toBe("true");
        expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
      });

      it("should store token in sessionStorage when rememberMe is false", () => {
        const futureDate = new Date(Date.now() + 3600000).toISOString();
        setToken("test-token", futureDate, false);

        expect(sessionStorage.getItem(TOKEN_KEY)).toBe("test-token");
        expect(sessionStorage.getItem(TOKEN_EXPIRE_KEY)).toBe(futureDate);
        expect(localStorage.getItem(REMEMBER_ME_KEY)).toBe("false");
        expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      });

      it("should clear token from both storages before setting new one", () => {
        localStorage.setItem(TOKEN_KEY, "old-token");
        sessionStorage.setItem(TOKEN_KEY, "old-token");

        const futureDate = new Date(Date.now() + 3600000).toISOString();
        setToken("new-token", futureDate, true);

        expect(localStorage.getItem(TOKEN_KEY)).toBe("new-token");
        expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
      });
    });

    describe("getToken", () => {
      it("should return token from localStorage if exists and not expired", () => {
        const futureDate = new Date(Date.now() + 3600000).toISOString();
        localStorage.setItem(TOKEN_KEY, "test-token");
        localStorage.setItem(TOKEN_EXPIRE_KEY, futureDate);

        expect(getToken()).toBe("test-token");
      });

      it("should return token from sessionStorage if not in localStorage", () => {
        const futureDate = new Date(Date.now() + 3600000).toISOString();
        sessionStorage.setItem(TOKEN_KEY, "session-token");
        sessionStorage.setItem(TOKEN_EXPIRE_KEY, futureDate);

        expect(getToken()).toBe("session-token");
      });

      it("should return null for expired token", () => {
        const pastDate = new Date(Date.now() - 3600000).toISOString();
        localStorage.setItem(TOKEN_KEY, "expired-token");
        localStorage.setItem(TOKEN_EXPIRE_KEY, pastDate);

        expect(getToken()).toBeNull();
      });

      it("should clear expired token", () => {
        const pastDate = new Date(Date.now() - 3600000).toISOString();
        localStorage.setItem(TOKEN_KEY, "expired-token");
        localStorage.setItem(TOKEN_EXPIRE_KEY, pastDate);

        getToken();

        expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      });

      it("should return null if no token exists", () => {
        expect(getToken()).toBeNull();
      });

      it("should return null if token exists but expire is missing", () => {
        localStorage.setItem(TOKEN_KEY, "test-token");
        expect(getToken()).toBeNull();
      });
    });

    describe("clearToken", () => {
      it("should clear token from both storages", () => {
        localStorage.setItem(TOKEN_KEY, "token");
        localStorage.setItem(TOKEN_EXPIRE_KEY, "expire");
        localStorage.setItem(REMEMBER_ME_KEY, "true");
        sessionStorage.setItem(TOKEN_KEY, "token");
        sessionStorage.setItem(TOKEN_EXPIRE_KEY, "expire");

        clearToken();

        expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
        expect(localStorage.getItem(TOKEN_EXPIRE_KEY)).toBeNull();
        expect(localStorage.getItem(REMEMBER_ME_KEY)).toBeNull();
        expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
        expect(sessionStorage.getItem(TOKEN_EXPIRE_KEY)).toBeNull();
      });
    });

    describe("wasRememberMeSelected", () => {
      it("should return true if remember me was selected", () => {
        localStorage.setItem(REMEMBER_ME_KEY, "true");
        expect(wasRememberMeSelected()).toBe(true);
      });

      it("should return false if remember me was not selected", () => {
        localStorage.setItem(REMEMBER_ME_KEY, "false");
        expect(wasRememberMeSelected()).toBe(false);
      });

      it("should return false if remember me key does not exist", () => {
        expect(wasRememberMeSelected()).toBe(false);
      });
    });
  });

  describe("ApiError", () => {
    it("should identify unauthorized error codes", () => {
      const error = new ApiError(
        ResultCode.PERMISSION_UNAUTHENTICATED,
        "Unauthorized",
      );
      expect(error.isUnauthorized).toBe(true);
      expect(error.isRateLimited).toBe(false);
    });

    it("should identify token expired error code", () => {
      const error = new ApiError(
        ResultCode.PERMISSION_TOKEN_EXPIRED,
        "Token expired",
      );
      expect(error.isUnauthorized).toBe(true);
    });

    it("should identify rate limited error codes", () => {
      const error = new ApiError(
        ResultCode.RATE_LIMIT_EXCEEDED,
        "Rate limited",
      );
      expect(error.isRateLimited).toBe(true);
      expect(error.isUnauthorized).toBe(false);
    });

    it("should set error properties correctly", () => {
      const error = new ApiError(500, "Server Error");
      expect(error.code).toBe(500);
      expect(error.message).toBe("Server Error");
      expect(error.name).toBe("ApiError");
    });

    it("should extend Error class", () => {
      const error = new ApiError(400, "Bad Request");
      expect(error).toBeInstanceOf(Error);
    });

    it("should have correct prototype chain", () => {
      const error = new ApiError(401, "Unauthorized");
      expect(Object.getPrototypeOf(error)).toBe(ApiError.prototype);
    });

    it("should identify all unauthorized codes", () => {
      const codes = [
        ResultCode.USER_NOT_LOGGED_IN,
        ResultCode.PERMISSION_UNAUTHENTICATED,
        ResultCode.PERMISSION_TOKEN_EXPIRED,
        ResultCode.PERMISSION_TOKEN_INVALID,
      ];

      codes.forEach((code) => {
        const error = new ApiError(code, "Test");
        expect(error.isUnauthorized).toBe(true);
      });
    });

    it("should identify all rate limited codes", () => {
      const codes = [
        ResultCode.RATE_LIMIT_EXCEEDED,
        ResultCode.PERMISSION_LIMIT,
        ResultCode.SERVICE_UNAVAILABLE,
        ResultCode.SERVICE_TIMEOUT,
        ResultCode.SERVICE_CIRCUIT_OPEN,
        ResultCode.SYSTEM_BUSY,
      ];

      codes.forEach((code) => {
        const error = new ApiError(code, "Test");
        expect(error.isRateLimited).toBe(true);
      });
    });

    it("should not identify normal errors as unauthorized or rate limited", () => {
      const normalCodes = [
        ResultCode.SUCCESS,
        ResultCode.FAIL,
        ResultCode.PARAM_ERROR,
        ResultCode.FILE_NOT_EXIST,
      ];

      normalCodes.forEach((code) => {
        const error = new ApiError(code, "Test");
        expect(error.isUnauthorized).toBe(false);
        expect(error.isRateLimited).toBe(false);
      });
    });
  });

  describe("createApiClient (Factory)", () => {
    const createMockFetch = (
      data: unknown,
      code = ResultCode.SUCCESS,
      message = "success",
    ) => {
      return vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code, message, data }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    };

    describe("GET requests", () => {
      it("should make GET request and return data", async () => {
        const responseData = { id: 1, name: "test" };
        const mockFetch = createMockFetch(responseData);
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        const result = await api.get("/users");

        expect(mockFetch).toHaveBeenCalledTimes(1);
        expect(result).toEqual(responseData);
      });

      it("should include query params in URL", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.get("/users", { params: { page: 1, size: 10 } });

        const [url] = mockFetch.mock.calls[0];
        expect(url).toContain("page=1");
        expect(url).toContain("size=10");
      });

      it("should skip null/undefined params", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.get("/users", {
          params: { page: 1, filter: null, sort: undefined },
        });

        const [url] = mockFetch.mock.calls[0];
        expect(url).toContain("page=1");
        expect(url).not.toContain("filter");
        expect(url).not.toContain("sort");
      });

      it("should include Authorization header when token is provided", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          getToken: () => "my-auth-token",
        });

        await api.get("/users");

        const [, options] = mockFetch.mock.calls[0];
        expect(options.headers.get("Authorization")).toBe(
          "Bearer my-auth-token",
        );
      });

      it("should skip Authorization header when skipAuth is true", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          getToken: () => "my-auth-token",
        });

        await api.get("/public", { skipAuth: true });

        const [, options] = mockFetch.mock.calls[0];
        expect(options.headers.get("Authorization")).toBeNull();
      });

      it("should include tenant ID header", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          tenantId: "tenant-123",
        });

        await api.get("/users");

        const [, options] = mockFetch.mock.calls[0];
        expect(options.headers.get("X-Tenant-ID")).toBe("tenant-123");
      });

      it("should include custom headers", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.get("/users", { headers: { "X-Custom": "value" } });

        const [, options] = mockFetch.mock.calls[0];
        expect(options.headers.get("X-Custom")).toBe("value");
      });
    });

    describe("POST requests", () => {
      it("should make POST request with JSON body", async () => {
        const requestBody = { name: "test" };
        const responseData = { id: 1, name: "test" };
        const mockFetch = createMockFetch(responseData);
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        const result = await api.post("/users", requestBody);

        const [, options] = mockFetch.mock.calls[0];
        expect(options.method).toBe("POST");
        expect(options.body).toBe(JSON.stringify(requestBody));
        expect(options.headers.get("Content-Type")).toBe("application/json");
        expect(result).toEqual(responseData);
      });

      it("should handle FormData body", async () => {
        const formData = new FormData();
        formData.append("file", new Blob(["test"]), "test.txt");
        const mockFetch = createMockFetch({ uploaded: true });
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.post("/upload", formData);

        const [, options] = mockFetch.mock.calls[0];
        expect(options.body).toBe(formData);
        expect(options.headers.has("Content-Type")).toBe(false);
      });

      it("should handle URLSearchParams body", async () => {
        const params = new URLSearchParams({
          username: "test",
          password: "pass",
        });
        const mockFetch = createMockFetch({ token: "abc" });
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.post("/login", params);

        const [, options] = mockFetch.mock.calls[0];
        expect(options.body).toBe(params);
        expect(options.headers.get("Content-Type")).toBe(
          "application/x-www-form-urlencoded",
        );
      });
    });

    describe("PUT requests", () => {
      it("should make PUT request", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.put("/users/1", { name: "updated" });

        const [, options] = mockFetch.mock.calls[0];
        expect(options.method).toBe("PUT");
      });
    });

    describe("DELETE requests", () => {
      it("should make DELETE request", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.delete("/users/1");

        const [, options] = mockFetch.mock.calls[0];
        expect(options.method).toBe("DELETE");
      });
    });

    describe("PATCH requests", () => {
      it("should make PATCH request", async () => {
        const mockFetch = createMockFetch({});
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await api.patch("/users/1", { status: "active" });

        const [, options] = mockFetch.mock.calls[0];
        expect(options.method).toBe("PATCH");
      });
    });

    describe("upload", () => {
      it("should upload FormData", async () => {
        const formData = new FormData();
        formData.append("file", new Blob(["content"]), "file.txt");
        const mockFetch = createMockFetch({ fileId: "123" });
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        const result = await api.upload("/files", formData);

        expect(result).toEqual({ fileId: "123" });
      });
    });

    describe("Error Handling", () => {
      it("should throw ApiError on non-success code", async () => {
        const createErrorResponse = () =>
          new Response(
            JSON.stringify({
              code: ResultCode.PARAM_ERROR,
              message: "参数错误",
              data: null,
            }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          );
        const mockFetch = vi
          .fn()
          .mockImplementation(() => Promise.resolve(createErrorResponse()));
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test")).rejects.toThrow(ApiError);
        await expect(api.get("/test")).rejects.toMatchObject({
          code: ResultCode.PARAM_ERROR,
          message: "参数错误",
        });
      });

      it("should preserve structured error detail payload", async () => {
        const mockFetch = vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              code: ResultCode.SERVICE_UNAVAILABLE,
              message: "服务暂时不可用",
              data: {
                traceId: "trace-1",
                detail: {
                  message: "请在 5 秒后重试",
                  reason: "rate-limit",
                },
                retryable: true,
                retryAfterSeconds: 5,
              },
            }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          ),
        );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test", { retries: 0 })).rejects.toMatchObject({
          code: ResultCode.SERVICE_UNAVAILABLE,
          message: "请在 5 秒后重试",
          traceId: "trace-1",
          detail: {
            message: "请在 5 秒后重试",
            reason: "rate-limit",
          },
          retryable: true,
          retryAfterSeconds: 5,
        });
      });

      it("should throw ApiError on non-JSON response", async () => {
        const mockFetch = vi.fn().mockResolvedValue(
          new Response("<html>Error</html>", {
            status: 200,
            headers: { "Content-Type": "text/html" },
          }),
        );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test")).rejects.toThrow("服务器响应格式错误");
      });

      it("should throw ApiError on JSON parse failure", async () => {
        const mockFetch = vi.fn().mockResolvedValue(
          new Response("not valid json", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
        );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test")).rejects.toThrow("响应解析失败");
      });

      it("should throw ApiError on timeout", async () => {
        vi.useFakeTimers();

        try {
          const mockFetch = vi
            .fn()
            .mockImplementation((_url: string, options?: RequestInit) => {
              return new Promise((_, reject) => {
                if (options?.signal) {
                  options.signal.addEventListener("abort", () => {
                    reject(new DOMException("Aborted", "AbortError"));
                  });
                }
              });
            });

          const api = createApiClient({
            baseUrl: "https://api.test.com",
            fetch: mockFetch,
          });

          const requestPromise = api.get("/test", { timeout: 5, retries: 0 });
          const assertion = expect(requestPromise).rejects.toThrow("请求超时");

          await vi.advanceTimersByTimeAsync(10);
          await assertion;
        } finally {
          vi.useRealTimers();
        }
      });

      it("should throw ApiError on network error", async () => {
        const mockFetch = vi
          .fn()
          .mockRejectedValue(new TypeError("Failed to fetch"));
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test", { retries: 0 })).rejects.toThrow(
          "网络连接失败",
        );
      });

      it("should call onUnauthorized callback on unauthorized error", async () => {
        const onUnauthorized = vi.fn();
        const mockFetch = vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              code: ResultCode.PERMISSION_UNAUTHENTICATED,
              message: "未授权",
              data: null,
            }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          ),
        );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          onUnauthorized,
        });

        await expect(api.get("/protected")).rejects.toThrow();
        expect(onUnauthorized).toHaveBeenCalled();
      });
    });

    describe("Retry Logic", () => {
      it("should retry on rate limit error", async () => {
        const mockFetch = vi
          .fn()
          .mockResolvedValueOnce(
            new Response(
              JSON.stringify({
                code: ResultCode.RATE_LIMIT_EXCEEDED,
                message: "Rate limited",
                data: null,
              }),
              { status: 200, headers: { "Content-Type": "application/json" } },
            ),
          )
          .mockResolvedValueOnce(
            new Response(
              JSON.stringify({
                code: ResultCode.SUCCESS,
                message: "success",
                data: { success: true },
              }),
              { status: 200, headers: { "Content-Type": "application/json" } },
            ),
          );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          retryDelayBase: 10,
        });

        const result = await api.get("/test", { retries: 1 });

        expect(mockFetch).toHaveBeenCalledTimes(2);
        expect(result).toEqual({ success: true });
      });

      it("should retry on network error", async () => {
        const mockFetch = vi
          .fn()
          .mockRejectedValueOnce(new TypeError("Failed to fetch"))
          .mockResolvedValueOnce(
            new Response(
              JSON.stringify({
                code: ResultCode.SUCCESS,
                message: "success",
                data: { success: true },
              }),
              { status: 200, headers: { "Content-Type": "application/json" } },
            ),
          );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          retryDelayBase: 10,
        });

        const result = await api.get("/test", { retries: 1 });

        expect(mockFetch).toHaveBeenCalledTimes(2);
        expect(result).toEqual({ success: true });
      });

      it("should not retry on non-retryable error", async () => {
        const mockFetch = vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              code: ResultCode.PARAM_ERROR,
              message: "参数错误",
              data: null,
            }),
            { status: 200, headers: { "Content-Type": "application/json" } },
          ),
        );
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
        });

        await expect(api.get("/test", { retries: 3 })).rejects.toThrow();
        expect(mockFetch).toHaveBeenCalledTimes(1);
      });

      it("should use custom maxRetries", async () => {
        const mockFetch = vi
          .fn()
          .mockRejectedValue(new TypeError("Failed to fetch"));
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          maxRetries: 2,
          retryDelayBase: 10,
        });

        await expect(api.get("/test")).rejects.toThrow();
        expect(mockFetch).toHaveBeenCalledTimes(3);
      });

      it("should override maxRetries in config", async () => {
        const mockFetch = vi
          .fn()
          .mockRejectedValue(new TypeError("Failed to fetch"));
        const api = createApiClient({
          baseUrl: "https://api.test.com",
          fetch: mockFetch,
          maxRetries: 5,
          retryDelayBase: 10,
        });

        await expect(api.get("/test", { retries: 1 })).rejects.toThrow();
        expect(mockFetch).toHaveBeenCalledTimes(2);
      });
    });
  });
});
