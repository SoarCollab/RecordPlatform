import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  return {
    getToken: vi.fn(),
    goto: vi.fn(),
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
    getCurrentUser: vi.fn(),
    updateUser: vi.fn(),
  };
});

vi.mock("$api/client", () => ({
  getToken: mocks.getToken,
}));

vi.mock("$app/navigation", () => ({
  goto: mocks.goto,
}));

vi.mock("$api/endpoints/auth", () => ({
  login: mocks.login,
  register: mocks.register,
  logout: mocks.logout,
  getCurrentUser: mocks.getCurrentUser,
  updateUser: mocks.updateUser,
}));

/**
 * 等待一次微任务队列，用于等待模块初始化异步逻辑完成。
 */
async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/**
 * 每次用例重新导入 auth store，隔离单例状态。
 *
 * @returns auth store 对象。
 */
async function loadAuthStore() {
  vi.resetModules();
  const mod = await import("./auth.svelte");
  await flushPromises();
  return mod.useAuth();
}

/**
 * 构造用户对象，便于在不同角色分支复用。
 *
 * @param role 用户角色。
 * @returns 标准用户对象。
 */
function createUser(role: string = "user") {
  return {
    id: "u1",
    username: "alice",
    nickname: "Alice",
    role,
    registerTime: "2025-01-01",
  };
}

describe("auth store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getToken.mockReturnValue(null);
    mocks.login.mockResolvedValue({ username: "alice", role: "user" });
    mocks.register.mockResolvedValue(undefined);
    mocks.logout.mockResolvedValue(undefined);
    mocks.getCurrentUser.mockResolvedValue(createUser("user"));
    mocks.updateUser.mockResolvedValue(createUser("user"));
  });

  it("无 token 初始化时应标记 initialized 且保持未登录", async () => {
    const auth = await loadAuthStore();

    expect(auth.initialized).toBe(true);
    expect(auth.user).toBeNull();
    expect(auth.isAuthenticated).toBe(false);
  });

  it("fetchUser 成功时应更新用户与派生字段", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockResolvedValue(createUser("admin"));

    const auth = await loadAuthStore();
    await auth.fetchUser();

    expect(auth.user?.username).toBe("alice");
    expect(auth.user?.role).toBe("admin");
    expect(auth.error).toBeNull();
  });

  it("fetchUser 失败时应清空用户并记录错误", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockRejectedValue(new Error("load fail"));

    const auth = await loadAuthStore();
    await auth.fetchUser();

    expect(auth.user).toBeNull();
    expect(auth.error).toBe("load fail");
    expect(auth.initialized).toBe(true);
  });

  it("login 成功后应拉取用户详情", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockResolvedValue(createUser("monitor"));

    const auth = await loadAuthStore();
    await auth.login({ username: "alice", password: "pass" }, { rememberMe: false });

    expect(mocks.login).toHaveBeenCalledWith(
      { username: "alice", password: "pass" },
      false,
    );
    expect(mocks.getCurrentUser).toHaveBeenCalled();
    expect(auth.user?.role).toBe("monitor");
    expect(auth.error).toBeNull();
  });

  it("login 失败时应透传异常并写入错误状态", async () => {
    mocks.login.mockRejectedValue(new Error("bad credentials"));

    const auth = await loadAuthStore();

    await expect(
      auth.login({ username: "alice", password: "bad" }, { rememberMe: true }),
    ).rejects.toThrow("bad credentials");

    expect(auth.error).toBe("bad credentials");
    expect(auth.isLoading).toBe(false);
  });

  it("login 在 rememberMe 未传时应默认传 true", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockResolvedValue(createUser("user"));

    const auth = await loadAuthStore();
    await auth.login({ username: "alice", password: "pass" });

    expect(mocks.login).toHaveBeenCalledWith(
      { username: "alice", password: "pass" },
      true,
    );
  });

  it("login 捕获非 Error 异常时应设置默认错误文案", async () => {
    mocks.login.mockRejectedValue("network-down");

    const auth = await loadAuthStore();
    await expect(
      auth.login({ username: "alice", password: "pass" }),
    ).rejects.toBe("network-down");

    expect(auth.error).toBe("登录失败");
  });

  it("register 成功后应走自动登录链路", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockResolvedValue(createUser("user"));

    const auth = await loadAuthStore();
    await auth.register(
      {
        username: "new-user",
        password: "pwd",
        nickname: "N",
        email: "n@test.com",
        code: "123456",
      },
      { rememberMe: false },
    );

    expect(mocks.register).toHaveBeenCalledWith(
      expect.objectContaining({ username: "new-user" }),
    );
    expect(mocks.login).toHaveBeenCalledWith(
      { username: "new-user", password: "pwd" },
      false,
    );
  });

  it("register 失败时应设置错误并抛出异常", async () => {
    mocks.register.mockRejectedValue(new Error("register failed"));

    const auth = await loadAuthStore();
    await expect(
      auth.register({
        username: "u",
        password: "p",
        nickname: "n",
        email: "e@test.com",
        code: "1",
      }),
    ).rejects.toThrow("register failed");

    expect(auth.error).toBe("register failed");
  });

  it("register 在 rememberMe 未传时应默认传 true", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockResolvedValue(createUser("user"));

    const auth = await loadAuthStore();
    await auth.register({
      username: "default-remember",
      password: "p",
      nickname: "n",
      email: "n@test.com",
      code: "123456",
    });

    expect(mocks.login).toHaveBeenCalledWith(
      { username: "default-remember", password: "p" },
      true,
    );
  });

  it("register 捕获非 Error 异常时应设置默认错误文案", async () => {
    mocks.register.mockRejectedValue("register-timeout");

    const auth = await loadAuthStore();
    await expect(
      auth.register({
        username: "u",
        password: "p",
        nickname: "n",
        email: "e@test.com",
        code: "1",
      }),
    ).rejects.toBe("register-timeout");

    expect(auth.error).toBe("注册失败");
  });

  it("logout 即使接口失败也应清空用户并跳转登录页", async () => {
    mocks.logout.mockRejectedValue(new Error("logout failed"));
    mocks.getToken.mockReturnValue("jwt");

    const auth = await loadAuthStore();
    await auth.fetchUser();

    await auth.logout();

    expect(auth.user).toBeNull();
    expect(mocks.goto).toHaveBeenCalledWith("/login");
  });

  it("updateProfile 成功应更新用户；失败应设置错误", async () => {
    mocks.getToken.mockReturnValue("jwt");
    const auth = await loadAuthStore();

    mocks.updateUser.mockResolvedValue(createUser("user"));
    await auth.updateProfile({ nickname: "new nick" });
    expect(auth.user?.nickname).toBe("Alice");

    mocks.updateUser.mockRejectedValue(new Error("update failed"));
    await expect(auth.updateProfile({ nickname: "bad" })).rejects.toThrow(
      "update failed",
    );
    expect(auth.error).toBe("update failed");

    auth.clearError();
    expect(auth.error).toBeNull();
  });

  it("fetchUser/updateProfile 捕获非 Error 异常时应走默认文案分支", async () => {
    mocks.getToken.mockReturnValue("jwt");
    mocks.getCurrentUser.mockRejectedValue({ reason: "non-error" });

    const auth = await loadAuthStore();
    await auth.fetchUser();

    expect(auth.error).toBe("获取用户信息失败");

    mocks.updateUser.mockRejectedValue({ reason: "non-error" });
    await expect(auth.updateProfile({ nickname: "x" })).rejects.toEqual({
      reason: "non-error",
    });
    expect(auth.error).toBe("更新失败");
  });

});
