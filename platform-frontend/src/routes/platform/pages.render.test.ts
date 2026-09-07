import "@testing-library/jest-dom/vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/svelte";
import {
  afterAll,
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { transferableAbortController } from "node:util";
import { beforeNavigate, goto } from "$app/navigation";
import { tick } from "svelte";
import * as fixture from "../../test/fixtures/platform";

const prefix = "/record-platform/api/v1/platform";
const alpha = fixture.PLATFORM_TEST_IDS.tenant;
const beta = "tenant-beta";
const writes: Array<{
  method: string;
  path: string;
  tenant: string | null;
  key: string | null;
  body: Record<string, unknown>;
}> = [];
let tenantRow = fixture.platformTenantFixture();
let configurations = fixture.platformConfigurationsFixture();
let memberRow = fixture.platformMemberFixture();
const server = setupServer(
  http.all(`*${prefix}/*`, async ({ request }) => {
    const path = new URL(request.url).pathname.slice(prefix.length);
    if (request.method !== "GET") {
      const body = (await request.json()) as Record<string, unknown>;
      writes.push({
        method: request.method,
        path,
        tenant: request.headers.get("X-Tenant-ID"),
        key: request.headers.get("Idempotency-Key"),
        body,
      });
      if (path === `/tenants/${alpha}` && typeof body.name === "string")
        tenantRow = {
          ...tenantRow,
          name: body.name,
          version: tenantRow.version + 1,
        };
      if (path.startsWith("/configuration/") && typeof body.value === "number")
        configurations = configurations.map((row) =>
          row.key === path.split("/")[2]
            ? { ...row, value: Number(body.value), version: 1 }
            : row,
        );
      return HttpResponse.json({
        code: 200,
        data: fixture.platformMutationFixture({
          resourceId: path === "/tenants" ? "created-tenant" : alpha,
          version:
            path.includes("invitations") || path.includes("users") ? null : 1,
        }),
      });
    }
    let data: unknown;
    if (path === "/session") data = fixture.platformSessionFixture();
    else if (path === "/overview") data = fixture.platformOverviewFixture();
    else if (path === "/resources/health")
      data = fixture.platformHealthFixture();
    else if (path === "/tenants")
      data = fixture.platformPageFixture([
        tenantRow,
        fixture.platformTenantFixture({
          id: beta,
          code: beta,
          name: "Beta tenant",
        }),
      ]);
    else if (path === "/users")
      data = fixture.platformPageFixture([fixture.platformUserFixture()]);
    else if (path.endsWith("/users"))
      data = fixture.platformPageFixture([
        path.includes(beta)
          ? fixture.platformMemberFixture({ username: "beta-member" })
          : memberRow,
      ]);
    else if (path.endsWith("/invitations"))
      data = [fixture.platformInvitationFixture()];
    else if (path.endsWith("/quota"))
      data = fixture.platformQuotaFixture({
        tenantId: path.split("/")[2] ?? alpha,
      });
    else if (path.endsWith("/usage"))
      data = fixture.platformUsageFixture({
        tenantId: path.split("/")[2] ?? alpha,
      });
    else if (path === "/configuration") data = configurations;
    else if (path.startsWith("/configuration/"))
      data = configurations.find((row) => row.key === path.split("/")[2]);
    else if (path === "/audit")
      data = fixture.platformPageFixture([fixture.platformAuditFixture()]);
    else if (path.startsWith("/audit/")) data = fixture.platformAuditFixture();
    else if (path === `/tenants/${alpha}`) data = tenantRow;
    else if (path === `/tenants/${beta}`)
      data = fixture.platformTenantFixture({
        id: beta,
        code: beta,
        name: "Beta tenant",
      });
    else
      throw new Error(`Unexpected fixture request: ${request.method} ${path}`);
    return HttpResponse.json({ code: 200, data });
  }),
);

// Preload the route graph under active network interception, outside timed behavior hooks.
vi.stubGlobal("AbortController", transferableAbortController().constructor);
server.listen({ onUnhandledRequest: "error" });
const [
  client,
  authModule,
  { default: Overview },
  { default: Tenants },
  { default: TenantDetail },
  { default: Users },
  { default: Resources },
  { default: Configuration },
  { default: Audit },
] = await Promise.all([
  import("$api/client"),
  import("$stores/auth.svelte"),
  import("./+page.svelte"),
  import("./tenants/+page.svelte"),
  import("./tenants/[tenantId]/+page.svelte"),
  import("./users/+page.svelte"),
  import("./resources/+page.svelte"),
  import("./configuration/+page.svelte"),
  import("./audit/+page.svelte"),
]);
const apiFunctions = await import("$api/endpoints/platform");

/** Enter the shared reason/review/final-confirm sequence through the actual dialog. */
async function confirm(): Promise<void> {
  await fireEvent.input(await screen.findByLabelText("操作原因"), {
    target: { value: "Approved browser fixture" },
  });
  await fireEvent.click(screen.getByRole("button", { name: "核对变更" }));
  expect(writes).toHaveLength(0);
  await fireEvent.click(
    await screen.findByRole("button", { name: "确认执行" }),
  );
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]?.tenant).toBe("0");
  expect(writes[0]?.key).toMatch(/^[0-9a-f-]{36}$/);
  expect(writes[0]?.body.reason).toBe("Approved browser fixture");
}
/** Create a controlled request race without introducing sleeps. */
function deferred() {
  let resolve: () => void = () => {};
  const promise = new Promise<void>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

beforeEach(async () => {
  writes.length = 0;
  tenantRow = fixture.platformTenantFixture();
  configurations = fixture.platformConfigurationsFixture();
  memberRow = fixture.platformMemberFixture();
  vi.spyOn(crypto, "randomUUID").mockReturnValue(fixture.PLATFORM_TEST_KEY);
  client.setToken("platform-pages", "2099-01-01", false, "platform");
  await authModule.useAuth().initializeSession();
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => {
  server.close();
  vi.unstubAllGlobals();
});

describe("all platform administration pages", () => {
  it("preserves a real mutation 401 when the pending dialog receives the login navigation", async () => {
    const canceled = vi.fn();
    vi.mocked(goto).mockImplementation(async () => {
      for (const [callback] of vi.mocked(beforeNavigate).mock.calls) {
        callback({ cancel: canceled } as never);
      }
      if (canceled.mock.calls.length) throw new Error("Navigation canceled");
    });
    server.use(
      http.post(`*${prefix}/tenants`, async ({ request }) => {
        writes.push({
          method: "POST",
          path: "/tenants",
          tenant: request.headers.get("X-Tenant-ID"),
          key: request.headers.get("Idempotency-Key"),
          body: (await request.json()) as Record<string, unknown>,
        });
        return HttpResponse.json(
          { code: 70006, message: "Session revoked" },
          { status: 401 },
        );
      }),
    );
    try {
      render(Tenants);
      await screen.findByText("Alpha tenant");
      await fireEvent.click(screen.getByRole("button", { name: "创建租户" }));
      await fireEvent.input(screen.getByLabelText("租户编码"), {
        target: { value: "created-tenant" },
      });
      await fireEvent.input(screen.getByLabelText("租户名称"), {
        target: { value: "Created tenant" },
      });
      await confirm();
      await screen.findByText("会话已失效，请重新登录。（70006）");
      expect(client.getToken()).toBeNull();
      expect(canceled).not.toHaveBeenCalled();
      expect(
        screen.queryByRole("button", { name: "使用同一操作重试" }),
      ).not.toBeInTheDocument();
    } finally {
      vi.mocked(goto).mockResolvedValue(undefined);
    }
  });

  it("does not review a configuration that becomes read-only in its authoritative detail response", async () => {
    configurations = [fixture.platformConfigurationFixture()];
    server.use(
      http.get(`*${prefix}/configuration/HIGH_FREQ_THRESHOLD`, () =>
        HttpResponse.json({
          code: 200,
          data: fixture.platformConfigurationFixture({ mutable: false }),
        }),
      ),
    );
    render(Configuration);
    await fireEvent.click(
      await screen.findByRole("button", { name: "修改配置" }),
    );
    await fireEvent.input(await screen.findByLabelText("操作原因"), {
      target: { value: "Approved fixture" },
    });
    expect(screen.getByRole("button", { name: "核对变更" })).toBeDisabled();
    expect(screen.getByText("此配置当前不可修改。")).toBeInTheDocument();
    expect(writes).toHaveLength(0);
  });
  it("renders measured overview counts and normalized service state", async () => {
    render(Overview);
    await screen.findByText("租户总数");
    expect(screen.getByText("用户总数")).toBeInTheDocument();
    expect(
      screen.getByRole("region", { name: "共享服务状态" }),
    ).toBeInTheDocument();
  });
  it("renders global user metadata without member emails or file actions", async () => {
    render(Users);
    await screen.findByRole("table", { name: "平台用户元数据" });
    expect(screen.getByText("member")).toBeInTheDocument();
    expect(screen.queryByText("member@example.test")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /预览|下载|模拟登录/ }),
    ).not.toBeInTheDocument();
  });
  it("lets an operator select a tenant and inspect shared and tenant-scoped resources", async () => {
    render(Resources);
    await screen.findByRole("option", { name: "Alpha tenant（tenant-alpha）" });
    await fireEvent.change(screen.getByLabelText("目标租户"), {
      target: { value: alpha },
    });
    await screen.findByText("已用存储");
    expect(screen.getByText("租户业务审计")).toBeInTheDocument();
    expect(screen.getByText(/观察模式/)).toBeInTheDocument();
  });
  it("shows unavailable configuration values without converting a null version into a write", async () => {
    configurations = [
      fixture.platformConfigurationFixture({
        state: "UNAVAILABLE",
        value: null,
        version: null,
      }),
    ];
    render(Configuration);
    await screen.findByText("值不可用");
    expect(screen.getByText("版本不可用")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修正配置" })).toBeDisabled();
    expect(writes).toEqual([]);
  });
  it("loads sanitized audit detail using the durable operation endpoint", async () => {
    render(Audit);
    await fireEvent.click(
      await screen.findByRole("button", { name: "查看记录" }),
    );
    const dialog = await screen.findByRole("dialog", { name: "平台操作详情" });
    await within(dialog).findByText(fixture.PLATFORM_TEST_IDS.operation);
    expect(within(dialog).getByText("结果版本")).toBeInTheDocument();
  });
  it("keeps unavailable overview metrics as an error instead of measured zero", async () => {
    server.use(
      http.get(`*${prefix}/overview`, () =>
        HttpResponse.json({ code: 50020, message: "private provider detail" }),
      ),
    );
    render(Overview);
    await screen.findByRole("alert");
    expect(screen.queryByText("租户总数")).not.toBeInTheDocument();
    expect(
      screen.queryByText("private provider detail"),
    ).not.toBeInTheDocument();
  });
  it("supports an empty tenant directory and disables invalid pagination", async () => {
    server.use(
      http.get(`*${prefix}/tenants`, () =>
        HttpResponse.json({ code: 200, data: fixture.platformPageFixture([]) }),
      ),
    );
    render(Tenants);
    await screen.findByText("没有符合条件的租户。");
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  });
  it("requests the next tenant page and renders its authoritative metadata", async () => {
    server.use(
      http.get(`*${prefix}/tenants`, ({ request }) => {
        const current = Number(
          new URL(request.url).searchParams.get("pageNum") ?? 1,
        );
        return HttpResponse.json({
          code: 200,
          data: fixture.platformPageFixture(
            [
              fixture.platformTenantFixture({
                name: current === 1 ? "Page one tenant" : "Page two tenant",
              }),
            ],
            { current, size: 20, total: 21 },
          ),
        });
      }),
    );
    render(Tenants);
    await screen.findByText("Page one tenant");
    await fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("Page two tenant");
    expect(screen.getByText(/第 2 \/ 2 页/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "上一页" })).toBeEnabled();
  });
});

describe("reviewed page mutations", () => {
  it("creates a tenant and opens the returned resource for first-admin invitation", async () => {
    render(Tenants);
    await screen.findByText("Alpha tenant");
    await fireEvent.click(screen.getByRole("button", { name: "创建租户" }));
    await fireEvent.input(screen.getByLabelText("租户编码"), {
      target: { value: "new-tenant" },
    });
    await fireEvent.input(screen.getByLabelText("租户名称"), {
      target: { value: "New tenant" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      method: "POST",
      path: "/tenants",
      body: { code: "new-tenant", name: "New tenant" },
    });
    await waitFor(() =>
      expect(goto).toHaveBeenCalledWith("/platform/tenants/created-tenant"),
    );
  });
  it("updates tenant metadata with its current zero version", async () => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "修改名称" }),
    );
    await fireEvent.input(screen.getByLabelText("新租户名称"), {
      target: { value: "Renamed tenant" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      method: "PUT",
      path: `/tenants/${alpha}`,
      body: { name: "Renamed tenant", expectedVersion: 0 },
    });
    await screen.findByText("操作成功");
  });
  it("disables a tenant with version and reason rather than deleting it", async () => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "停用租户" }),
    );
    await confirm();
    expect(writes[0]).toMatchObject({
      method: "PUT",
      path: `/tenants/${alpha}/status`,
      body: { status: 0, expectedVersion: 0 },
    });
  });
  it("changes a member role through the explicit target tenant", async () => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "修改角色" }),
    );
    await fireEvent.change(screen.getByLabelText("目标角色"), {
      target: { value: "monitor" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      path: `/tenants/${alpha}/users/${fixture.PLATFORM_TEST_IDS.user}/role`,
      body: { role: "monitor" },
    });
  });
  it.each([
    ["停用成员", "status"],
    ["撤销会话", "sessions/revoke"],
  ])("executes %s only after the shared review", async (label, suffix) => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(await screen.findByRole("button", { name: label }));
    await confirm();
    expect(writes[0]?.path).toBe(
      `/tenants/${alpha}/users/${fixture.PLATFORM_TEST_IDS.user}/${suffix}`,
    );
  });
  it("invites a first administrator without exposing invitation credentials", async () => {
    tenantRow = fixture.platformTenantFixture({ memberCount: 0 });
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "邀请首位管理员" }),
    );
    expect(screen.getByLabelText("目标角色")).toHaveValue("admin");
    await fireEvent.input(screen.getByLabelText("邀请邮箱"), {
      target: { value: "first-admin@example.invalid" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      method: "POST",
      path: `/tenants/${alpha}/invitations`,
      body: {
        email: "first-admin@example.invalid",
        role: "admin",
        expiresInHours: 72,
      },
    });
    expect(
      screen.queryByRole("button", { name: /复制.*链接|密码/ }),
    ).not.toBeInTheDocument();
  });
  it("revokes invitation metadata with a DELETE reason body", async () => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "撤销邀请" }),
    );
    await confirm();
    expect(writes[0]).toMatchObject({
      method: "DELETE",
      path: `/tenants/${alpha}/invitations/${fixture.PLATFORM_TEST_IDS.invitation}`,
      body: { reason: "Approved browser fixture" },
    });
  });
  it("updates shared quota controls with reviewed zero version", async () => {
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "修改配额" }),
    );
    await fireEvent.input(screen.getByLabelText("存储限额（字节）"), {
      target: { value: "2048" },
    });
    await fireEvent.input(screen.getByLabelText("文件数量限额"), {
      target: { value: "20" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      path: `/tenants/${alpha}/quota`,
      body: { maxStorageBytes: 2048, maxFileCount: 20, expectedVersion: 0 },
    });
  });
  it("reads one allowlisted configuration before its versioned change", async () => {
    render(Configuration);
    const row = await screen.findByRole("region", {
      name: "HIGH_FREQ_THRESHOLD",
    });
    await fireEvent.click(
      within(row).getByRole("button", { name: "修改配置" }),
    );
    await fireEvent.input(await screen.findByLabelText("新配置值"), {
      target: { value: "200" },
    });
    await confirm();
    expect(writes[0]).toMatchObject({
      path: "/configuration/HIGH_FREQ_THRESHOLD",
      body: { value: 200, expectedVersion: 0 },
    });
  });
  it("keeps quota conflict recovery blocked when the authoritative reload fails", async () => {
    server.use(
      http.put(`*${prefix}/tenants/${alpha}/quota`, async ({ request }) => {
        writes.push({
          method: "PUT",
          path: `/tenants/${alpha}/quota`,
          tenant: request.headers.get("X-Tenant-ID"),
          key: request.headers.get("Idempotency-Key"),
          body: (await request.json()) as Record<string, unknown>,
        });
        return HttpResponse.json({ code: 50022, message: "conflict" });
      }),
    );
    render(TenantDetail, { data: { tenantId: alpha } });
    await fireEvent.click(
      await screen.findByRole("button", { name: "修改配额" }),
    );
    await confirm();
    await screen.findByRole("button", { name: "重新读取并编辑" });
    server.use(
      http.get(`*${prefix}/tenants/${alpha}/quota`, () =>
        HttpResponse.json({ code: 50020, message: "unavailable" }),
      ),
    );
    await fireEvent.click(
      screen.getByRole("button", { name: "重新读取并编辑" }),
    );
    await screen.findByText("读取最新状态失败，请稍后重试。");
    expect(writes).toHaveLength(1);
    expect(
      screen.queryByRole("button", { name: "核对变更" }),
    ).not.toBeInTheDocument();
  });
});

describe("page request ordering", () => {
  it("keeps a newer tenant search when the older response arrives last", async () => {
    const requests = vi.spyOn(apiFunctions, "listPlatformTenants");
    render(Tenants);
    await screen.findByText("Alpha tenant");
    const started = deferred();
    const release = deferred();
    server.use(
      http.get(`*${prefix}/tenants`, async ({ request }) => {
        const keyword = new URL(request.url).searchParams.get("keyword");
        if (keyword === "old") {
          started.resolve();
          await release.promise;
        }
        return HttpResponse.json({
          code: 200,
          data: fixture.platformPageFixture([
            fixture.platformTenantFixture({
              name: keyword === "old" ? "Old result" : "New result",
            }),
          ]),
        });
      }),
    );
    await fireEvent.input(screen.getByLabelText("名称或编码"), {
      target: { value: "old" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await started.promise;
    const oldResponse = requests.mock.results.at(-1)?.value;
    expect(oldResponse).toBeInstanceOf(Promise);
    await fireEvent.input(screen.getByLabelText("名称或编码"), {
      target: { value: "new" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await screen.findByText("New result");
    release.resolve();
    await oldResponse;
    await tick();
    await waitFor(() =>
      expect(screen.queryByText("Old result")).not.toBeInTheDocument(),
    );
    expect(screen.getByText("New result")).toBeInTheDocument();
  });
  it("does not let an old failure end a newer request's loading state", async () => {
    const requests = vi.spyOn(apiFunctions, "listPlatformTenants");
    render(Tenants);
    await screen.findByText("Alpha tenant");
    const oldStarted = deferred();
    const newStarted = deferred();
    const oldRelease = deferred();
    const newRelease = deferred();
    server.use(
      http.get(`*${prefix}/tenants`, async ({ request }) => {
        if (new URL(request.url).searchParams.get("keyword") === "old") {
          oldStarted.resolve();
          await oldRelease.promise;
          return HttpResponse.json({ code: 50020, message: "old failure" });
        }
        newStarted.resolve();
        await newRelease.promise;
        return HttpResponse.json({
          code: 200,
          data: fixture.platformPageFixture([
            fixture.platformTenantFixture({ name: "New result" }),
          ]),
        });
      }),
    );
    await fireEvent.input(screen.getByLabelText("名称或编码"), {
      target: { value: "old" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await oldStarted.promise;
    const oldResponse = requests.mock.results.at(-1)?.value;
    expect(oldResponse).toBeInstanceOf(Promise);
    const oldOutcome = Promise.resolve(oldResponse).catch(() => undefined);
    await fireEvent.input(screen.getByLabelText("名称或编码"), {
      target: { value: "new" },
    });
    await fireEvent.click(screen.getByRole("button", { name: "查询" }));
    await newStarted.promise;
    oldRelease.resolve();
    await oldOutcome;
    await tick();
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("正在查询租户"),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    newRelease.resolve();
    await screen.findByText("New result");
  });
  it("discards an old tenant detail response after the target route changes", async () => {
    const requests = vi.spyOn(apiFunctions, "getPlatformTenant");
    const started = deferred();
    const release = deferred();
    server.use(
      http.get(`*${prefix}/tenants/${alpha}`, async () => {
        started.resolve();
        await release.promise;
        return HttpResponse.json({ code: 200, data: tenantRow });
      }),
    );
    const view = render(TenantDetail, { data: { tenantId: alpha } });
    await started.promise;
    const oldResponse = requests.mock.results.at(-1)?.value;
    expect(oldResponse).toBeInstanceOf(Promise);
    await view.rerender({ data: { tenantId: beta } });
    await screen.findByRole("heading", { name: "Beta tenant" });
    release.resolve();
    await oldResponse;
    await tick();
    await waitFor(() =>
      expect(
        screen.queryByRole("heading", { name: "Alpha tenant" }),
      ).not.toBeInTheDocument(),
    );
    expect(screen.getByText("beta-member")).toBeInTheDocument();
  });
});
