import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AccountVO, AuthScope, LoginRequest } from "$api/types";

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  getCurrentUser: vi.fn(),
  updateUser: vi.fn(),
  getPlatformSession: vi.fn(),
  clearAllDownloads: vi.fn(),
  resetBadges: vi.fn(),
  goto: vi.fn(),
}));
vi.mock("$api/endpoints/auth", () => ({
  login: mocks.login,
  register: mocks.register,
  logout: mocks.logout,
  getCurrentUser: mocks.getCurrentUser,
  updateUser: mocks.updateUser,
}));
vi.mock("$api/endpoints/platform", () => ({
  getPlatformSession: mocks.getPlatformSession,
}));
vi.mock("$stores/download.svelte", () => ({
  useDownload: () => ({ clearAllDownloads: mocks.clearAllDownloads }),
}));
vi.mock("$stores/badges.svelte", () => ({
  useBadges: () => ({ reset: mocks.resetBadges }),
}));
vi.mock("$app/navigation", () => ({ goto: mocks.goto }));

let client: typeof import("$api/client");
const expiry = "2099-01-01T00:00:00Z";

/** Build a complete ordinary profile without inventing platform profile fields. */
function profile(overrides: Partial<AccountVO> = {}): AccountVO {
  return {
    id: "user-a",
    username: "alice",
    nickname: "Alice",
    scope: "tenant",
    role: "user",
    registerTime: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}
/** Control profile and command races without timing assumptions. */
function deferred<T>() {
  let resolve: (value: T) => void = () => {};
  let reject: (reason: unknown) => void = () => {};
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
/** Load one isolated store while retaining the real credential lifecycle. */
async function store() {
  return (await import("./auth.svelte")).useAuth();
}

beforeEach(async () => {
  vi.resetModules();
  vi.clearAllMocks();
  client = await import("$api/client");
  mocks.getCurrentUser.mockResolvedValue(profile());
  mocks.getPlatformSession.mockResolvedValue({
    actorId: "platform-actor",
    username: "operator",
    scope: "platform",
    systemTenantId: 0,
    capabilities: ["platform:tenant:read", "platform:overview:read"],
  });
  mocks.login.mockImplementation(
    async (data: LoginRequest, remember: boolean, scope: AuthScope) => {
      client.setToken(data.username, expiry, remember, scope);
      return {
        token: data.username,
        expire: expiry,
        scope,
        role: scope === "platform" ? "platform_admin" : "user",
        username: data.username,
      };
    },
  );
  mocks.register.mockResolvedValue(undefined);
  mocks.logout.mockImplementation(async () => {
    client.clearToken();
  });
  mocks.updateUser.mockResolvedValue(profile({ nickname: "Updated" }));
  mocks.clearAllDownloads.mockResolvedValue(undefined);
});

describe("explicit scope-aware authentication store", () => {
  it("does not initialize a protected session when imported", async () => {
    client.setToken("remembered", expiry, true, "platform");
    const auth = await store();
    expect(auth.initialized).toBe(false);
    expect(auth.isAuthenticated).toBe(false);
    expect(mocks.getCurrentUser).not.toHaveBeenCalled();
    expect(mocks.getPlatformSession).not.toHaveBeenCalled();
  });

  it("completes explicit anonymous initialization without requests", async () => {
    const auth = await store();
    await auth.initializeSession();
    expect(auth.initialized).toBe(true);
    expect(auth.isLoading).toBe(false);
    expect(auth.scope).toBeNull();
    expect(auth.user).toBeNull();
    expect(auth.displayName).toBe("");
    expect(auth.username).toBe("");
    expect(mocks.getCurrentUser).not.toHaveBeenCalled();
  });

  it.each(["user", "admin", "monitor"])(
    "retains tenant-only role getters for %s",
    async (role) => {
      client.setToken("tenant-zero-or-business", expiry, false, "tenant");
      mocks.getCurrentUser.mockResolvedValue(profile({ role }));
      const auth = await store();
      await auth.initializeSession();
      expect(auth.scope).toBe("tenant");
      expect(auth.isAuthenticated).toBe(true);
      expect(auth.isAdmin).toBe(role === "admin");
      expect(auth.isMonitor).toBe(role === "monitor");
      expect(auth.isAdminOrMonitor).toBe(role !== "user");
      expect(auth.isPlatformAdmin).toBe(false);
      expect(auth.hasPlatformCapability("platform:tenant:read")).toBe(false);
      expect(auth.platformSession).toBeNull();
      expect(auth.displayName).toBe("Alice");
      expect(mocks.getPlatformSession).not.toHaveBeenCalled();
    },
  );

  it("stores platform session separately and grants only validated capabilities", async () => {
    client.setToken("platform", expiry, true, "platform");
    const auth = await store();
    await auth.initializeSession();
    expect(auth.isPlatformAdmin).toBe(true);
    expect(auth.scope).toBe("platform");
    expect(auth.user).toBeNull();
    expect(auth.isAdminOrMonitor).toBe(false);
    expect(auth.displayName).toBe("operator");
    expect(auth.username).toBe("operator");
    expect(auth.hasPlatformCapability("platform:tenant:read")).toBe(true);
    expect(auth.hasPlatformCapability("platform:tenant:write")).toBe(false);
    expect(mocks.getCurrentUser).not.toHaveBeenCalled();
    await auth.initializeSession();
    expect(mocks.getPlatformSession).toHaveBeenCalledOnce();
  });

  it("deduplicates overlapping initialization and validates the same credential once", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    const pending = deferred<AccountVO>();
    mocks.getCurrentUser.mockReturnValue(pending.promise);
    const auth = await store();
    const first = auth.initializeSession();
    const second = auth.initializeSession();
    expect(mocks.getCurrentUser).toHaveBeenCalledOnce();
    expect(auth.isLoading).toBe(true);
    pending.resolve(profile());
    await Promise.all([first, second]);
    expect(auth.isAuthenticated).toBe(true);
    expect(auth.isLoading).toBe(false);
  });

  it.each(["success", "failure"])(
    "ignores a stale profile %s after another identity is validated",
    async (outcome) => {
      client.setToken("old", expiry, true, "tenant");
      const old = deferred<AccountVO>();
      mocks.getCurrentUser.mockReturnValueOnce(old.promise);
      const auth = await store();
      const first = auth.initializeSession().catch((error: unknown) => error);
      client.setToken("new", expiry, false, "tenant");
      mocks.getCurrentUser.mockResolvedValue(profile({ username: "new-user" }));
      await auth.initializeSession();
      if (outcome === "success")
        old.resolve(profile({ username: "stale-user" }));
      else old.reject(new Error("stale failure"));
      await first;
      expect(auth.username).toBe("new-user");
      expect(auth.error).toBeNull();
      expect(auth.isLoading).toBe(false);
    },
  );

  it("does not restore a delayed profile after logout", async () => {
    client.setToken("old", expiry, false, "tenant");
    const old = deferred<AccountVO>();
    mocks.getCurrentUser.mockReturnValue(old.promise);
    const auth = await store();
    const first = auth.initializeSession().catch((error: unknown) => error);
    await auth.logout();
    old.resolve(profile());
    await first;
    expect(auth.user).toBeNull();
    expect(auth.platformSession).toBeNull();
    expect(auth.isAuthenticated).toBe(false);
  });

  it("passes named scope and persistence through login before session restoration", async () => {
    const auth = await store();
    await auth.login(
      { username: "operator", password: "fixture" },
      { mode: "platform", rememberMe: false },
    );
    expect(mocks.login).toHaveBeenCalledWith(
      { username: "operator", password: "fixture" },
      false,
      "platform",
    );
    expect(auth.isPlatformAdmin).toBe(true);
    expect(mocks.getCurrentUser).not.toHaveBeenCalled();
  });

  it("defaults login and registration to tenant scope", async () => {
    const auth = await store();
    await auth.login({ username: "alice", password: "fixture" });
    expect(mocks.login).toHaveBeenLastCalledWith(
      { username: "alice", password: "fixture" },
      true,
      "tenant",
    );
    await auth.register(
      {
        username: "registered",
        password: "fixture",
        email: "fixture@example.invalid",
        code: "123456",
      },
      { rememberMe: false, mode: "platform" },
    );
    expect(mocks.register).toHaveBeenCalledOnce();
    expect(mocks.login).toHaveBeenLastCalledWith(
      { username: "registered", password: "fixture" },
      false,
      "tenant",
    );
  });

  it.each([new Error("bad credentials"), "unavailable"])(
    "exposes a current login error without fabricating identity",
    async (failure) => {
      mocks.login.mockRejectedValue(failure);
      const auth = await store();
      await expect(
        auth.login({ username: "alice", password: "fixture" }),
      ).rejects.toBe(failure);
      expect(auth.error).toBe(
        failure instanceof Error ? failure.message : "登录失败",
      );
      expect(auth.isLoading).toBe(false);
      expect(auth.isAuthenticated).toBe(false);
      auth.clearError();
      expect(auth.error).toBeNull();
    },
  );

  it("prevents an anonymous registration completion from replacing a newer session", async () => {
    const registration = deferred<void>();
    mocks.register.mockReturnValue(registration.promise);
    const auth = await store();
    const pending = auth
      .register({
        username: "registered",
        password: "fixture",
        email: "fixture@example.invalid",
        code: "123456",
      })
      .catch((error: unknown) => error);
    client.setToken("new", expiry, false, "tenant");
    registration.resolve();
    expect(await pending).toMatchObject({ name: "AbortError" });
    expect(mocks.login).not.toHaveBeenCalled();
    expect(auth.error).toBeNull();
  });

  it("does not surface an old login failure over a newer restored credential", async () => {
    const request = deferred<never>();
    mocks.login.mockReturnValue(request.promise);
    const auth = await store();
    const pending = auth
      .login({ username: "old", password: "fixture" })
      .catch((error: unknown) => error);
    client.setToken("new-platform", expiry, true, "platform");
    await auth.initializeSession();
    request.reject(new Error("old login failure"));
    await pending;
    expect(auth.isPlatformAdmin).toBe(true);
    expect(auth.error).toBeNull();
  });

  it("does not surface an old login's profile failure over a newer restored credential", async () => {
    const request = deferred<AccountVO>();
    mocks.getCurrentUser.mockReturnValueOnce(request.promise);
    const auth = await store();
    const pending = auth
      .login({ username: "old", password: "fixture" })
      .catch((error: unknown) => error);
    await vi.waitFor(() => expect(mocks.getCurrentUser).toHaveBeenCalledOnce());
    client.setToken("new-platform", expiry, true, "platform");
    await auth.initializeSession();
    request.reject(new Error("old profile failure"));
    await pending;
    expect(auth.isPlatformAdmin).toBe(true);
    expect(auth.error).toBeNull();
  });

  it("records registration failures and retains the anonymous state", async () => {
    mocks.register.mockRejectedValue(new Error("registration failed"));
    const auth = await store();
    await expect(
      auth.register({
        username: "registered",
        password: "fixture",
        email: "fixture@example.invalid",
        code: "123456",
      }),
    ).rejects.toThrow("registration failed");
    expect(auth.error).toBe("registration failed");
    expect(auth.isLoading).toBe(false);
  });

  it("cleans tenant caches only for the ended tenant session", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    const auth = await store();
    await auth.initializeSession();
    await auth.logout();
    expect(mocks.clearAllDownloads).toHaveBeenCalledOnce();
    expect(mocks.resetBadges).toHaveBeenCalledOnce();
    expect(mocks.goto).toHaveBeenCalledWith("/login");
    expect(auth.initialized).toBe(true);
  });

  it("platform logout clears local caches without fetching a tenant profile", async () => {
    client.setToken("platform", expiry, false, "platform");
    const auth = await store();
    await auth.initializeSession();
    await auth.logout();
    expect(mocks.clearAllDownloads).toHaveBeenCalledOnce();
    expect(mocks.resetBadges).toHaveBeenCalledOnce();
    expect(mocks.getCurrentUser).not.toHaveBeenCalled();
    expect(auth.isLoading).toBe(false);
  });

  it("a newer login wins over asynchronous old-session cleanup", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    const auth = await store();
    await auth.initializeSession();
    const cleanup = deferred<void>();
    mocks.clearAllDownloads.mockReturnValue(cleanup.promise);
    const logout = auth.logout();
    await vi.waitFor(() =>
      expect(mocks.clearAllDownloads).toHaveBeenCalledOnce(),
    );
    await auth.login(
      { username: "new-login", password: "fixture" },
      { mode: "platform" },
    );
    cleanup.resolve();
    await logout;
    expect(auth.isPlatformAdmin).toBe(true);
    expect(mocks.goto).not.toHaveBeenCalled();
  });

  it("supports profile changes and display-name fallback only in tenant scope", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    const auth = await store();
    await auth.initializeSession();
    await auth.updateProfile({ nickname: "Updated" });
    expect(auth.displayName).toBe("Updated");
    mocks.getCurrentUser.mockResolvedValue(profile({ nickname: "" }));
    await auth.fetchUser();
    expect(auth.displayName).toBe("alice");
    client.setToken("platform", expiry, false, "platform");
    await auth.initializeSession();
    await expect(
      auth.updateProfile({ nickname: "forbidden" }),
    ).rejects.toMatchObject({ code: 70002 });
  });

  it("does not apply a stale profile edit to a new identity", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    const auth = await store();
    await auth.initializeSession();
    const edit = deferred<AccountVO>();
    mocks.updateUser.mockReturnValue(edit.promise);
    const pending = auth
      .updateProfile({ nickname: "old" })
      .catch((error: unknown) => error);
    client.setToken("platform", expiry, false, "platform");
    await auth.initializeSession();
    edit.resolve(profile({ nickname: "stale" }));
    expect(await pending).toMatchObject({ name: "AbortError" });
    expect(auth.displayName).toBe("operator");
    expect(auth.error).toBeNull();
  });

  it("rejects malformed tenant scope and recovers from explicit initialization errors", async () => {
    client.setToken("tenant", expiry, false, "tenant");
    mocks.getCurrentUser.mockResolvedValue(
      profile({ scope: "platform", role: "platform_admin" }),
    );
    const auth = await store();
    await expect(auth.initializeSession()).rejects.toMatchObject({
      code: 70002,
    });
    expect(auth.isAuthenticated).toBe(false);
    expect(client.getToken()).toBe("tenant");
    mocks.getCurrentUser.mockResolvedValue(profile());
    await auth.initializeSession();
    expect(auth.error).toBeNull();
    mocks.getCurrentUser.mockRejectedValue(new Error("profile unavailable"));
    await auth.fetchUser();
    expect(auth.error).toBe("profile unavailable");
    expect(auth.isAuthenticated).toBe(false);
  });

  it("platform guards reject anonymous, tenant and missing-capability identities before page work", async () => {
    const guards = await import("$utils/platformAccess");
    await expect(guards.requirePlatformSession()).rejects.toMatchObject({
      status: 303,
      location: "/login?mode=platform",
    });
    client.setToken("tenant", expiry, false, "tenant");
    await expect(guards.requirePlatformSession()).rejects.toMatchObject({
      status: 303,
      location: "/dashboard",
    });
    client.setToken("platform", expiry, false, "platform");
    await expect(
      guards.requirePlatformCapability("platform:tenant:write"),
    ).rejects.toMatchObject({ status: 403 });
    await expect(
      guards.requirePlatformCapability("platform:tenant:read"),
    ).resolves.toMatchObject({ systemTenantId: 0 });
  });
});
