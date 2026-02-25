import { beforeEach, describe, expect, it, vi } from "vitest";

const clientMocks = vi.hoisted(() => {
  return {
    api: {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
      patch: vi.fn(),
      upload: vi.fn(),
    },
    setToken: vi.fn(),
    clearToken: vi.fn(),
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
  setToken: clientMocks.setToken,
  clearToken: clientMocks.clearToken,
}));

import type { AccountVO } from "../types";
import * as authApi from "./auth";

function createAccount(overrides: Partial<AccountVO> = {}): AccountVO {
  return {
    id: "user-1",
    username: "alice",
    role: "user",
    registerTime: "2025-01-01",
    ...overrides,
  } as AccountVO;
}

describe("auth endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("login 应调用登录接口并设置 token（默认 rememberMe=true）", async () => {
    clientMocks.api.post.mockResolvedValue({
      token: "token-1",
      expire: "2099-01-01",
      username: "alice",
      role: "user",
    });

    const result = await authApi.login({ username: "alice", password: "p" });

    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/auth/login",
      { username: "alice", password: "p" },
      { skipAuth: true },
    );
    expect(clientMocks.setToken).toHaveBeenCalledWith(
      "token-1",
      "2099-01-01",
      true,
    );
    expect(result.username).toBe("alice");
  });

  it("login 支持 rememberMe=false", async () => {
    clientMocks.api.post.mockResolvedValue({
      token: "token-2",
      expire: "2099-01-01",
      username: "alice",
      role: "user",
    });

    await authApi.login({ username: "alice", password: "p" }, false);

    expect(clientMocks.setToken).toHaveBeenCalledWith(
      "token-2",
      "2099-01-01",
      false,
    );
  });

  it("register 应走 skipAuth 注册接口", async () => {
    clientMocks.api.post.mockResolvedValue("ok");

    await authApi.register({
      username: "bob",
      password: "pass",
      nickname: "B",
      email: "b@test.com",
      code: "123456",
    });

    expect(clientMocks.api.post).toHaveBeenCalledWith(
      "/auth/register",
      expect.objectContaining({ username: "bob" }),
      { skipAuth: true },
    );
  });

  it("logout 在接口失败时也应 finally 清理 token", async () => {
    clientMocks.api.post.mockRejectedValue(new Error("logout failed"));

    await expect(authApi.logout()).rejects.toThrow("logout failed");
    expect(clientMocks.clearToken).toHaveBeenCalledTimes(1);
  });

  it("getCurrentUser 应返回用户信息", async () => {
    const account = createAccount({ id: "id-1" });
    clientMocks.api.get.mockResolvedValue(account);

    const result = await authApi.getCurrentUser();

    expect(clientMocks.api.get).toHaveBeenCalledWith("/users/info");
    expect(result.id).toBe("id-1");
    expect(result.username).toBe("alice");
  });

  it("updateUser 应更新并返回用户信息", async () => {
    const account = createAccount({ nickname: "new-name" });
    clientMocks.api.put.mockResolvedValue(account);

    const result = await authApi.updateUser({ nickname: "new-name" });

    expect(clientMocks.api.put).toHaveBeenCalledWith("/users/info", {
      nickname: "new-name",
    });
    expect(result.username).toBe("alice");
  });

  it("refreshToken 应更新存储中的 token", async () => {
    clientMocks.api.post.mockResolvedValue({
      token: "refresh-token",
      expire: "2099-12-31",
    });

    const result = await authApi.refreshToken();

    expect(clientMocks.api.post).toHaveBeenCalledWith("/auth/tokens/refresh");
    expect(clientMocks.setToken).toHaveBeenCalledWith(
      "refresh-token",
      "2099-12-31",
    );
    expect(result.token).toBe("refresh-token");
  });

  it("验证码与重置相关接口应透传参数", async () => {
    clientMocks.api.get.mockResolvedValue(undefined);
    clientMocks.api.post.mockResolvedValue(undefined);
    clientMocks.api.put.mockResolvedValue(undefined);

    await authApi.sendRegisterCode("u@example.com");
    await authApi.sendResetCode("u@example.com");
    await authApi.confirmResetCode({ email: "u@example.com", code: "1111" });
    await authApi.resetPassword({
      email: "u@example.com",
      code: "1111",
      password: "new-pass",
    });

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/auth/verification-codes",
      null,
      {
        params: { email: "u@example.com", type: "register" },
        skipAuth: true,
      },
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/auth/verification-codes",
      null,
      {
        params: { email: "u@example.com", type: "reset" },
        skipAuth: true,
      },
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      3,
      "/auth/password-resets/confirm",
      { email: "u@example.com", code: "1111" },
      { skipAuth: true },
    );
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      1,
      "/auth/password-resets",
      { email: "u@example.com", code: "1111", password: "new-pass" },
      { skipAuth: true },
    );
  });

  it("getSseToken 应返回短期令牌", async () => {
    clientMocks.api.post.mockResolvedValue({ sseToken: "sse-short-token" });

    const result = await authApi.getSseToken();

    expect(clientMocks.api.post).toHaveBeenCalledWith("/auth/tokens/sse");
    expect(result.sseToken).toBe("sse-short-token");
  });
});
