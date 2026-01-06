import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { ResultCode } from "./types";
import {
  getToken,
  setToken,
  clearToken,
  wasRememberMeSelected,
  ApiError,
  TOKEN_KEY,
  TOKEN_EXPIRE_KEY,
  REMEMBER_ME_KEY,
} from "./client";

/**
 * Tests for the API client module.
 *
 * Note: API request tests (api.get, api.post, etc.) require proper fetch mocking
 * which is complex in vitest with ESM modules. These can be added later using
 * MSW (Mock Service Worker) for a more robust solution.
 *
 * Currently tested:
 * - Token management (setToken, getToken, clearToken, wasRememberMeSelected)
 * - ApiError class behavior
 */
describe("API Client", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    sessionStorage.clear();
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
  });
});
