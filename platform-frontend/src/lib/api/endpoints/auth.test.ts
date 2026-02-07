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

/**
 * 构造标准化前的账户对象，便于测试 id/externalId 兼容逻辑。
 *
 * @param overrides 字段覆盖。
 * @returns 可被后续 normalize 处理的账户对象。
 */
function createRawAccount(overrides: Partial<AccountVO> = {}): AccountVO {
  return {
    id: "",
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

  it("getCurrentUser: 优先使用 id 字段", async () => {
    clientMocks.api.get.mockResolvedValue(
      createRawAccount({ id: "id-1", externalId: "ext-1" } as AccountVO),
    );

    const result = await authApi.getCurrentUser();

    expect(result.id).toBe("id-1");
    expect((result as AccountVO & { externalId?: string }).externalId).toBe(
      "ext-1",
    );
  });

  it("getCurrentUser: 当 id 缺失时回退 externalId", async () => {
    clientMocks.api.get.mockResolvedValue(
      createRawAccount({ id: undefined as unknown as string, externalId: "ext-2" }),
    );

    const result = await authApi.getCurrentUser();

    expect(result.id).toBe("ext-2");
  });

  it("updateUser: 当 id/externalId 都缺失时回退空串", async () => {
    clientMocks.api.put.mockResolvedValue(
      createRawAccount({ id: undefined as unknown as string, externalId: undefined }),
    );

    const result = await authApi.updateUser({ nickname: "new-name" });

    expect(clientMocks.api.put).toHaveBeenCalledWith("/users/info", {
      nickname: "new-name",
    });
    expect(result.id).toBe("");
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

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(1, "/auth/verification-codes", null, {
      params: { email: "u@example.com", type: "register" },
      skipAuth: true,
    });
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(2, "/auth/verification-codes", null, {
      params: { email: "u@example.com", type: "reset" },
      skipAuth: true,
    });
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
