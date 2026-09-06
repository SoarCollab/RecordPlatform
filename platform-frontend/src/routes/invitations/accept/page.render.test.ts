import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/svelte";
import { goto } from "$app/navigation";
import { ResultCode } from "$api/types";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
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

const notifications = vi.hoisted(() => ({
  notifications: [],
  success: vi.fn(),
  error: vi.fn(),
  dismiss: vi.fn(),
}));
const auth = vi.hoisted(() => ({ isAuthenticated: false, logout: vi.fn() }));
const download = vi.hoisted(() => ({
  tasks: [],
  activeTasks: [],
  completedTasks: [],
  restoreTasks: vi.fn(),
}));
const sse = vi.hoisted(() => ({ disconnect: vi.fn() }));

vi.mock("$stores/notifications.svelte", () => ({
  useNotifications: () => notifications,
}));
vi.mock("$stores/auth.svelte", () => ({ useAuth: () => auth }));
vi.mock("$stores/download.svelte", () => ({ useDownload: () => download }));
vi.mock("$stores/sse.svelte", () => ({ useSSE: () => sse }));

const server = setupServer();
const acceptanceUrl = "*/record-platform/api/v1/public/invitations/accept";
const invitationToken = "opaque-fragment-invitation";

// Install the transport before collection imports because the default API client captures fetch once.
vi.stubGlobal("AbortController", transferableAbortController().constructor);
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
const client = await import("$api/client");
// Compile the real root graph during collection, outside the behavioral hook/test timeout.
const { default: InvitationRoute } =
  await import("./InvitationRoute.test.svelte");

/** Enters valid account details through the rendered invitation form. */
async function submitInvitation(): Promise<void> {
  await fireEvent.input(screen.getByLabelText("用户名"), {
    target: { value: "invited-user" },
  });
  await fireEvent.input(screen.getByLabelText("昵称（可选）"), {
    target: { value: "Invited User" },
  });
  await fireEvent.input(screen.getByLabelText("密码"), {
    target: { value: "invitation-password123" },
  });
  await fireEvent.click(screen.getByRole("button", { name: "接受邀请" }));
}

describe("invitation acceptance page", () => {
  beforeEach(() => {
    auth.isAuthenticated = false;
    auth.logout.mockReset().mockResolvedValue(undefined);
    history.replaceState(
      null,
      "",
      `/invitations/accept#token=${invitationToken}`,
    );
  });

  afterEach(() => {
    cleanup();
    server.resetHandlers();
    history.replaceState(null, "", "/");
  });

  afterAll(() => {
    server.close();
    vi.unstubAllGlobals();
  });

  it.each([
    { session: "remembered", rememberMe: true },
    { session: "tab", rememberMe: false },
  ])(
    "renders the public route with an authenticated $session session and accepts without changing that identity",
    async ({ rememberMe }) => {
      auth.isAuthenticated = true;
      client.setToken("existing-session-token", "2099-01-01", rememberMe);
      const received = vi.fn();
      server.use(
        http.post(acceptanceUrl, async ({ request }) => {
          received({
            path: new URL(request.url).pathname,
            search: new URL(request.url).search,
            body: await request.json(),
            authorization: request.headers.get("Authorization"),
            tenantId: request.headers.get("X-Tenant-ID"),
          });
          return HttpResponse.json({
            code: ResultCode.SUCCESS,
            message: "success",
            data: { id: "U-invited", username: "invited-user" },
          });
        }),
      );

      const view = render(InvitationRoute);

      await waitFor(() => expect(window.location.hash).toBe(""));
      expect(
        screen.getByRole("heading", { name: "接受成员邀请" }),
      ).toBeTruthy();
      expect(download.restoreTasks).toHaveBeenCalledTimes(1);
      expect(received).not.toHaveBeenCalled();
      expect(goto).not.toHaveBeenCalled();
      expect(view.container.innerHTML).not.toContain(invitationToken);

      await submitInvitation();

      await waitFor(() =>
        expect(
          screen.getByRole("heading", { name: "已加入受邀租户" }),
        ).toBeTruthy(),
      );
      expect(goto).not.toHaveBeenCalled();
      expect(auth.logout).not.toHaveBeenCalled();
      expect(auth.isAuthenticated).toBe(true);
      expect(screen.getByRole("button", { name: "登录新账号" })).toBeTruthy();
      expect(
        screen.getByRole("button", { name: "继续使用当前账号" }),
      ).toBeTruthy();
      expect(view.container.innerHTML).not.toContain(invitationToken);
      expect(view.container.innerHTML).not.toContain("invitation-password123");
      expect(received).toHaveBeenCalledExactlyOnceWith({
        path: "/record-platform/api/v1/public/invitations/accept",
        search: "",
        body: {
          token: invitationToken,
          username: "invited-user",
          nickname: "Invited User",
          password: "invitation-password123",
        },
        authorization: null,
        tenantId: null,
      });
      expect(notifications.success).toHaveBeenCalledExactlyOnceWith(
        "加入成功",
        "请使用新账号登录",
      );
      expect(notifications.error).not.toHaveBeenCalled();
      const storage = rememberMe ? localStorage : sessionStorage;
      expect(storage.getItem(client.TOKEN_KEY)).toBe("existing-session-token");
    },
  );

  it.each([
    { session: "absent", expires: null },
    { session: "expired", expires: "2000-01-01" },
  ])(
    "keeps direct login navigation for an $session session",
    async ({ expires }) => {
      if (expires) client.setToken("expired-session-token", expires, false);
      server.use(
        http.post(acceptanceUrl, () =>
          HttpResponse.json({
            code: ResultCode.SUCCESS,
            data: { id: "U-invited" },
          }),
        ),
      );

      render(InvitationRoute);
      await submitInvitation();

      await waitFor(() =>
        expect(goto).toHaveBeenCalledExactlyOnceWith("/login", {
          replaceState: true,
        }),
      );
      expect(auth.logout).not.toHaveBeenCalled();
    },
  );

  it("continues the current account only when explicitly chosen", async () => {
    auth.isAuthenticated = true;
    client.setToken("existing-session-token", "2099-01-01", true);
    server.use(
      http.post(acceptanceUrl, () =>
        HttpResponse.json({
          code: ResultCode.SUCCESS,
          data: { id: "U-invited" },
        }),
      ),
    );

    render(InvitationRoute);
    await submitInvitation();
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "继续使用当前账号" }),
      ).toBeTruthy(),
    );
    expect(goto).not.toHaveBeenCalled();

    await fireEvent.click(
      screen.getByRole("button", { name: "继续使用当前账号" }),
    );

    expect(goto).toHaveBeenCalledExactlyOnceWith("/dashboard", {
      replaceState: true,
    });
    expect(auth.logout).not.toHaveBeenCalled();
    expect(client.getToken()).toBe("existing-session-token");
  });

  it("uses the existing logout flow only after choosing to log in as the new account", async () => {
    auth.isAuthenticated = true;
    client.setToken("existing-session-token", "2099-01-01", true);
    auth.logout.mockImplementation(async () => {
      client.clearToken();
      auth.isAuthenticated = false;
      await goto("/login");
    });
    server.use(
      http.post(acceptanceUrl, () =>
        HttpResponse.json({
          code: ResultCode.SUCCESS,
          data: { id: "U-invited" },
        }),
      ),
    );

    render(InvitationRoute);
    await submitInvitation();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "登录新账号" })).toBeTruthy(),
    );
    expect(auth.logout).not.toHaveBeenCalled();
    expect(client.getToken()).toBe("existing-session-token");

    await fireEvent.click(screen.getByRole("button", { name: "登录新账号" }));

    await waitFor(() => expect(auth.logout).toHaveBeenCalledTimes(1));
    expect(goto).toHaveBeenCalledExactlyOnceWith("/login");
    expect(client.getToken()).toBeNull();
  });

  it("reports a failed explicit account switch without replaying acceptance", async () => {
    auth.isAuthenticated = true;
    client.setToken("existing-session-token", "2099-01-01", true);
    auth.logout.mockRejectedValueOnce(new Error("switch unavailable"));
    const acceptance = vi.fn(() =>
      HttpResponse.json({
        code: ResultCode.SUCCESS,
        data: { id: "U-invited" },
      }),
    );
    server.use(http.post(acceptanceUrl, acceptance));

    render(InvitationRoute);
    await submitInvitation();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "登录新账号" })).toBeTruthy(),
    );
    await fireEvent.click(screen.getByRole("button", { name: "登录新账号" }));

    await waitFor(() =>
      expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
        "切换账号失败",
        "switch unavailable",
      ),
    );
    expect(acceptance).toHaveBeenCalledTimes(1);
    expect(goto).not.toHaveBeenCalled();
  });

  it("rejects query-only invitation tokens before making a request", async () => {
    history.replaceState(null, "", "/invitations/accept?token=query-token");
    const response = vi.fn(() =>
      HttpResponse.json({ code: ResultCode.SUCCESS, data: null }),
    );
    server.use(http.post(acceptanceUrl, response));

    render(InvitationRoute);
    await submitInvitation();

    expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
      "邀请无效",
      "链接缺少邀请令牌",
    );
    expect(response).not.toHaveBeenCalled();
    expect(notifications.success).not.toHaveBeenCalled();
    expect(goto).not.toHaveBeenCalled();
  });

  it.each([
    { failure: "rate limiting", message: "请求频繁，请稍后重试" },
    { failure: "network failure", message: "网络连接失败" },
  ])(
    "shows $failure without automatically replaying the single-use invitation",
    async ({ failure, message }) => {
      const response = vi.fn(() =>
        failure === "network failure"
          ? HttpResponse.error()
          : HttpResponse.json({
              code: ResultCode.PERMISSION_LIMIT,
              message,
              data: { retryable: true, retryAfterSeconds: 1 },
            }),
      );
      server.use(http.post(acceptanceUrl, response));

      render(InvitationRoute);
      await submitInvitation();

      await waitFor(() =>
        expect(notifications.error).toHaveBeenCalledExactlyOnceWith(
          "接受邀请失败",
          message,
        ),
      );
      expect(response).toHaveBeenCalledTimes(1);
      expect(screen.getByRole("button", { name: "接受邀请" })).toHaveProperty(
        "disabled",
        false,
      );
      expect(window.location.hash).toBe("");
      expect(notifications.success).not.toHaveBeenCalled();
      expect(goto).not.toHaveBeenCalled();
    },
  );
});
