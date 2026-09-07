import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/svelte";
import { goto, onNavigate } from "$app/navigation";
import { ResultCode } from "$api/types";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { transferableAbortController } from "node:util";
import { afterAll, describe, expect, it, vi } from "vitest";

const server = setupServer();
const oldToken = "locally-current-but-server-revoked-session";
// Preserve requests made during collection; global beforeEach intentionally clears vi.fn call histories.
const protectedRequests: string[] = [];
const acceptance = vi.fn();

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
server.use(
  http.get("*/record-platform/api/v1/users/info", ({ request }) => {
    protectedRequests.push(request.headers.get("Authorization") ?? "");
    return HttpResponse.json(
      {
        code: ResultCode.PERMISSION_UNAUTHENTICATED,
        message: "The old session was revoked",
      },
      { status: 401 },
    );
  }),
  http.post(
    "*/record-platform/api/v1/public/invitations/accept",
    async ({ request }) => {
      acceptance({
        authorization: request.headers.get("Authorization"),
        tenantId: request.headers.get("X-Tenant-ID"),
        body: await request.json(),
      });
      return HttpResponse.json({
        code: ResultCode.SUCCESS,
        data: { id: "U-invited" },
      });
    },
  ),
);
server.listen({ onUnhandledRequest: "error" });
const client = await import("$api/client");
// Keep a real old session in place before loading the route so eager auth imports remain observable.
client.setToken(oldToken, "2099-01-01", true);
// Cold Svelte transforms belong to collection; auth-store initialization remains deferred until the control below.
const [{ default: InvitationRoute }, { useDownload }, { useNotifications }] =
  await Promise.all([
    import("./InvitationRoute.test.svelte"),
    import("$stores/download.svelte"),
    import("$stores/notifications.svelte"),
  ]);
const tokenAfterCollection = client.getToken();

describe("public invitation route with a server-revoked session", () => {
  afterAll(() => {
    cleanup();
    useNotifications().dismissAll();
    server.close();
    history.replaceState(null, "", "/");
    vi.unstubAllGlobals();
  });

  it("accepts through the real root layout and stores before any protected session validation", async () => {
    expect(protectedRequests).toEqual([]);
    expect(tokenAfterCollection).toBe(oldToken);
    // Restore only storage cleared by the global test setup; retain collection-time request evidence.
    client.setToken(oldToken, "2099-01-01", true);
    history.replaceState(
      null,
      "",
      "/invitations/accept#token=opaque-invitation-capability",
    );
    // No auth, download, SSE, notification store, page, or layout is replaced in this regression.
    render(InvitationRoute);
    await screen.findByRole("heading", { name: "接受成员邀请" });
    expect(onNavigate).toHaveBeenCalledOnce();
    expect(useDownload().initialized).toBe(false);
    expect(window.location.hash).toBe("");
    expect(protectedRequests).toEqual([]);
    expect(goto).not.toHaveBeenCalled();

    await fireEvent.input(screen.getByLabelText("用户名"), {
      target: { value: "invited-user" },
    });
    await fireEvent.input(screen.getByLabelText("密码"), {
      target: { value: "new-member-password" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "接受邀请" }));

    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "已加入受邀租户" }),
      ).toBeTruthy(),
    );
    expect(acceptance).toHaveBeenCalledExactlyOnceWith({
      authorization: null,
      tenantId: null,
      body: {
        token: "opaque-invitation-capability",
        username: "invited-user",
        password: "new-member-password",
      },
    });
    expect(protectedRequests).toEqual([]);
    expect(goto).not.toHaveBeenCalled();
    expect(client.getToken()).toBe(oldToken);
    expect(useDownload().initialized).toBe(false);

    // Import stays public; explicit destination initialization rejects the same old session.
    const { useAuth } = await import("$stores/auth.svelte");
    expect(protectedRequests).toEqual([]);
    await expect(useAuth().initializeSession()).rejects.toMatchObject({
      isUnauthorized: true,
    });
    await waitFor(() =>
      expect(protectedRequests).toEqual([`Bearer ${oldToken}`]),
    );
    await waitFor(() =>
      expect(goto).toHaveBeenCalledExactlyOnceWith("/login", {
        replaceState: true,
      }),
    );
    expect(client.getToken()).toBeNull();
  });
});
