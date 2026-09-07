import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/svelte";
import { tick } from "svelte";
import { goto } from "$app/navigation";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { createHash, webcrypto } from "node:crypto";
import { transferableAbortController } from "node:util";
import {
  afterAll,
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import type { PlatformCapability } from "$api/types";

const route = vi.hoisted(() => {
  let value = {
    url: new URL("http://localhost/platform"),
    params: {},
    route: { id: "/platform" },
    status: 200,
    error: null,
    data: {},
    form: null,
  };
  const listeners = new Set<(page: typeof value) => void>();
  return {
    set(path: string) {
      value = { ...value, url: new URL(path, "http://localhost") };
      for (const listener of listeners) listener(value);
    },
    page: {
      subscribe(listener: (page: typeof value) => void) {
        listeners.add(listener);
        listener(value);
        return () => listeners.delete(listener);
      },
    },
  };
});
vi.mock("$app/stores", () => ({
  page: route.page,
  navigating: {
    subscribe: (listener: (value: null) => void) => {
      listener(null);
      return () => {};
    },
  },
  updated: {
    subscribe: (listener: (value: boolean) => void) => {
      listener(false);
      return () => {};
    },
    check: async () => false,
  },
}));

const base = "*/record-platform/api/v1";
const expiry = "2099-01-01T00:00:00Z";
const requests: Array<{
  path: string;
  tenant: string | null;
  token: string | null;
}> = [];
let capabilities: PlatformCapability[] = [
  "platform:overview:read",
  "platform:tenant:read",
];
let validationGate: Promise<void> | null = null;
const onEnter = vi.fn();

/** Control a network operation without relying on arbitrary response delays. */
function deferred() {
  let resolve: () => void = () => {};
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** Record actual scope headers at the interception boundary. */
function observe(request: Request): void {
  requests.push({
    path: new URL(request.url).pathname,
    tenant: request.headers.get("X-Tenant-ID"),
    token: request.headers.get("Authorization"),
  });
}

const server = setupServer(
  http.get(`${base}/platform/session`, async ({ request }) => {
    observe(request);
    if (validationGate) await validationGate;
    return HttpResponse.json({
      code: 200,
      data: {
        actorId: "operator",
        username: "platform-operator",
        scope: "platform",
        systemTenantId: 0,
        capabilities,
      },
    });
  }),
  http.get(`${base}/platform/overview`, ({ request }) => {
    observe(request);
    return HttpResponse.json({
      code: 200,
      data: {
        tenants: 4,
        activeTenants: 3,
        disabledTenants: 1,
        users: 8,
        activeUsers: 7,
        state: "AVAILABLE",
      },
    });
  }),
  http.get(`${base}/users/info`, ({ request }) => {
    observe(request);
    return HttpResponse.json({
      code: 200,
      data: {
        id: "tenant-user",
        username: "tenant-user",
        scope: "tenant",
        role: "admin",
        registerTime: "2026-01-01",
      },
    });
  }),
  http.post(`${base}/auth/logout`, ({ request }) => {
    observe(request);
    return HttpResponse.json({ code: 200, data: null });
  }),
  http.post(`${base}/public/invitations/accept`, ({ request }) => {
    observe(request);
    return HttpResponse.json({ code: 200, data: { id: "invited-user" } });
  }),
);

// Install interception before importing real auth, download and route modules.
vi.stubGlobal("AbortController", transferableAbortController().constructor);
vi.stubGlobal("crypto", webcrypto);
vi.stubGlobal(
  "matchMedia",
  vi.fn((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
);
server.listen({ onUnhandledRequest: "error" });
const [
  client,
  { useAuth },
  { useDownload },
  downloadStorage,
  { useNotifications },
  { default: Boundary },
] = await Promise.all([
  import("$api/client"),
  import("$stores/auth.svelte"),
  import("$stores/download.svelte"),
  import("$utils/downloadStorage"),
  import("$stores/notifications.svelte"),
  import("./PlatformBoundary.test.svelte"),
]);

/** Dispatch the browser event that another document receives for shared storage writes. */
function storageChanged(key: string | null): void {
  const event = new StorageEvent("storage", { key });
  Object.defineProperty(event, "storageArea", { value: localStorage });
  window.dispatchEvent(event);
}

/** Seed a real persisted download so exclusion checks cannot pass on an empty queue. */
async function seedDownload(token: string): Promise<void> {
  await downloadStorage.saveTask({
    id: "tenant-download",
    fileHash: "tenant-file-hash",
    fileName: "tenant-private-record.pdf",
    fileSize: 16,
    contentType: "application/pdf",
    totalChunks: 1,
    source: { type: "owned" },
    authContextHash: createHash("sha256").update(`1:${token}`).digest("hex"),
    createdAt: Date.now(),
  });
}

/** Render an admitted platform session through the real root, layout and overview page. */
async function renderPlatform(): Promise<void> {
  client.setToken("platform-token", expiry, true, "platform");
  render(Boundary, { onEnter });
  await screen.findByRole("heading", { name: "平台总览" });
  await screen.findByText("租户总数");
}

beforeEach(async () => {
  requests.length = 0;
  capabilities = ["platform:overview:read", "platform:tenant:read"];
  validationGate = null;
  route.set("/platform");
  client.clearToken();
  await useDownload().clearAllDownloads();
});
afterEach(async () => {
  cleanup();
  useNotifications().dismissAll();
  server.resetHandlers();
  await useDownload().clearAllDownloads();
});
afterAll(() => {
  server.close();
  vi.unstubAllGlobals();
});

describe("composed platform authentication boundary", () => {
  it("validates fixed-zero identity before mounting the real page and excludes persisted tenant downloads", async () => {
    await seedDownload("platform-token");
    await renderPlatform();
    expect(onEnter).toHaveBeenCalledOnce();
    expect(requests.map(({ path }) => path)).toEqual([
      "/record-platform/api/v1/platform/session",
      "/record-platform/api/v1/platform/overview",
    ]);
    expect(requests.every(({ tenant }) => tenant === "0")).toBe(true);
    expect(
      screen.getByRole("navigation", { name: "平台管理导航" }),
    ).toBeTruthy();
    expect(screen.queryByText("下载管理")).toBeNull();
    expect(screen.queryByText("tenant-private-record.pdf")).toBeNull();
    expect(screen.queryByPlaceholderText(/搜索/)).toBeNull();
    expect(useDownload().initialized).toBe(false);
    expect(await downloadStorage.getTask("tenant-download")).not.toBeNull();
  });

  it.each(["home", "login", "register", "tenant"] as const)(
    "hides a nonempty download manager while a platform identity visits %s",
    async (surface) => {
      client.setToken("platform-token", expiry, true, "platform");
      await seedDownload("platform-token");
      await useDownload().restoreTasks();
      expect(useDownload().tasks).toHaveLength(1);
      route.set(
        surface === "home"
          ? "/"
          : surface === "tenant"
            ? "/dashboard"
            : `/${surface}`,
      );
      render(Boundary, { surface, onEnter });
      await waitFor(() =>
        expect(goto).toHaveBeenCalledWith("/platform", { replaceState: true }),
      );
      expect(onEnter).not.toHaveBeenCalled();
      expect(screen.queryByText("下载管理")).toBeNull();
      expect(screen.queryByText("tenant-private-record.pdf")).toBeNull();
      expect(
        requests.some(
          ({ path }) =>
            path.includes("/users/info") || path.includes("/tokens/sse"),
        ),
      ).toBe(false);
      expect(
        requests.some(({ path }) => path.endsWith("/platform/overview")),
      ).toBe(false);
    },
  );

  it.each(["anonymous", "tenant", "missing-capability"])(
    "blocks %s before protected children or page requests",
    async (identity) => {
      if (identity !== "anonymous")
        client.setToken(
          identity,
          expiry,
          true,
          identity === "tenant" ? "tenant" : "platform",
        );
      if (identity === "missing-capability")
        capabilities = ["platform:tenant:read"];
      render(Boundary, { onEnter });
      if (identity === "missing-capability")
        await screen.findByRole("heading", { name: "没有页面访问权限" });
      else
        await waitFor(() =>
          expect(goto).toHaveBeenCalledWith(
            identity === "tenant" ? "/dashboard" : "/login?mode=platform",
            { replaceState: true },
          ),
        );
      expect(onEnter).not.toHaveBeenCalled();
      expect(screen.queryByTestId("protected-child")).toBeNull();
      expect(
        requests.some(({ path }) => path.endsWith("/platform/overview")),
      ).toBe(false);
    },
  );

  it("rechecks page capability on a reused layout during child navigation", async () => {
    await renderPlatform();
    route.set("/platform/users");
    await screen.findByRole("heading", { name: "没有页面访问权限" });
    expect(screen.queryByTestId("protected-child")).toBeNull();
    expect(screen.queryByRole("heading", { name: "平台总览" })).toBeNull();
    expect(onEnter).toHaveBeenCalledOnce();
  });

  it("handles an expired credential during reactive navigation without mutating derived state", async () => {
    await renderPlatform();
    const clock = vi.spyOn(Date, "now").mockReturnValue(Date.parse(expiry) + 1);
    try {
      route.set("/platform/users");
      await tick();
      expect(screen.queryByTestId("protected-child")).toBeNull();
    } finally {
      clock.mockRestore();
    }
  });

  it("unmounts protected content when the current page request loses its session", async () => {
    await renderPlatform();
    server.use(
      http.get(`${base}/platform/overview`, () =>
        HttpResponse.json(
          { code: 70006, message: "Revoked session" },
          { status: 401 },
        ),
      ),
    );
    await fireEvent.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() =>
      expect(screen.queryByTestId("protected-child")).toBeNull(),
    );
    expect(client.getToken()).toBeNull();
    expect(useAuth().isAuthenticated).toBe(false);
    expect(goto).toHaveBeenCalled();
  });

  it.each([client.TOKEN_KEY, null])(
    "removes protected content after another tab clears storage key %s",
    async (key) => {
      await renderPlatform();
      if (key === null) localStorage.clear();
      else localStorage.removeItem(client.TOKEN_KEY);
      storageChanged(key);
      await waitFor(() =>
        expect(screen.queryByTestId("protected-child")).toBeNull(),
      );
      expect(goto).toHaveBeenCalledWith("/login?mode=platform", {
        replaceState: true,
      });
    },
  );

  it("withholds prior children while a replacement from another tab is being validated", async () => {
    await renderPlatform();
    const gate = deferred();
    validationGate = gate.promise;
    capabilities = ["platform:tenant:read"];
    localStorage.setItem(client.TOKEN_KEY, "replacement-platform-token");
    storageChanged(client.TOKEN_KEY);
    try {
      await waitFor(() =>
        expect(screen.queryByTestId("protected-child")).toBeNull(),
      );
      expect(useAuth().isAuthenticated).toBe(false);
    } finally {
      gate.resolve();
    }
    await screen.findByRole("heading", { name: "没有页面访问权限" });
    expect(
      requests.filter(({ path }) => path.endsWith("/platform/overview")),
    ).toHaveLength(1);
  });

  it("does not reload or re-admit the old platform identity while logout is pending", async () => {
    await renderPlatform();
    const started = deferred();
    const release = deferred();
    server.use(
      http.post(`${base}/auth/logout`, async ({ request }) => {
        observe(request);
        started.resolve();
        await release.promise;
        return HttpResponse.json({ code: 200, data: null });
      }),
    );
    const logout = useAuth().logout();
    await started.promise;
    await tick();
    try {
      expect(
        requests.filter(({ path }) => path.endsWith("/platform/session")),
      ).toHaveLength(1);
      expect(screen.queryByTestId("protected-child")).toBeNull();
    } finally {
      release.resolve();
      await logout;
    }
    expect(useAuth().isAuthenticated).toBe(false);
  });

  it("admits a newer credential while the old logout is still pending", async () => {
    await renderPlatform();
    const started = deferred();
    const release = deferred();
    server.use(
      http.post(`${base}/auth/logout`, async () => {
        started.resolve();
        await release.promise;
        return HttpResponse.json({ code: 200, data: null });
      }),
    );
    const logout = useAuth().logout();
    await started.promise;
    client.setToken("new-platform-token", expiry, false, "platform");
    try {
      await screen.findByRole("heading", { name: "平台总览" });
    } finally {
      release.resolve();
      await logout;
    }
    expect(client.getToken()).toBe("new-platform-token");
    expect(useAuth().isPlatformAdmin).toBe(true);
    expect(goto).not.toHaveBeenCalled();
  });

  it("keeps public invitation acceptance anonymous and continues the remembered platform account", async () => {
    client.setToken("platform-token", expiry, true, "platform");
    route.set("/invitations/accept");
    history.replaceState(
      null,
      "",
      "/invitations/accept#token=invitation-capability",
    );
    render(Boundary, { surface: "invitation", onEnter });
    await fireEvent.input(screen.getByLabelText("用户名"), {
      target: { value: "invited-user" },
    });
    await fireEvent.input(screen.getByLabelText("密码"), {
      target: { value: "new-password-fixture" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "接受邀请" }));
    await screen.findByRole("heading", { name: "已加入受邀租户" });
    expect(requests).toEqual([
      {
        path: "/record-platform/api/v1/public/invitations/accept",
        tenant: null,
        token: null,
      },
    ]);
    expect(client.getToken()).toBe("platform-token");
    expect(useDownload().initialized).toBe(false);
    await fireEvent.click(
      screen.getByRole("button", { name: "继续使用当前账号" }),
    );
    expect(goto).toHaveBeenCalledWith("/platform", { replaceState: true });
    expect(requests).toHaveLength(1);
  });

  it("preserves the root download host for anonymous public share flows", async () => {
    route.set("/share/public-capability");
    render(Boundary, { surface: "public", onEnter });
    await waitFor(() => expect(useDownload().initialized).toBe(true));
    expect(screen.getByText("Public share content")).toBeTruthy();
    expect(requests).toEqual([]);
  });
});

it("ends an uninitialized public-route session and clears its persisted downloads without restoring a guest queue", async () => {
  client.setToken("remembered-tenant", expiry, true, "tenant");
  await seedDownload("remembered-tenant");
  route.set("/invitations/accept");
  render(Boundary, { surface: "public", onEnter });
  expect(useAuth().initialized).toBe(false);
  expect(useDownload().initialized).toBe(false);
  expect(screen.queryByText("tenant-private-record.pdf")).toBeNull();
  await useAuth().logout();
  expect(await downloadStorage.getTask("tenant-download")).toBeNull();
  expect(useDownload().tasks).toHaveLength(0);
  expect(client.getToken()).toBeNull();
  expect(requests.map(({ path }) => path)).toEqual([
    "/record-platform/api/v1/auth/logout",
  ]);
  expect(screen.queryByText("下载管理")).toBeNull();
});
