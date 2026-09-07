import "@testing-library/jest-dom/vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/svelte";
import { goto } from "$app/navigation";
import type { components } from "$api/types/generated";
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

const { auth, notifications } = vi.hoisted(() => ({
  auth: {
    role: "admin",
    get isAdminOrMonitor() {
      return this.role === "admin" || this.role === "monitor";
    },
  },
  notifications: { error: vi.fn(), success: vi.fn() },
}));

vi.mock("$stores/auth.svelte", () => ({ useAuth: () => auth }));
vi.mock("$stores/notifications.svelte", () => ({
  useNotifications: () => notifications,
}));

// The safe tenant projection contains no database IDs or timestamps.
const safeConfigs = [
  {
    configKey: "HIGH_FREQ_THRESHOLD",
    configValue: "100",
    description: "Operations per five-minute window",
  },
  {
    configKey: "FAILED_LOGIN_THRESHOLD",
    configValue: "10",
    description: "Failed logins per hour",
  },
  {
    configKey: "ERROR_RATE_THRESHOLD",
    configValue: "20",
    description: "Error-rate alert percentage",
  },
  {
    configKey: "LOG_RETENTION_DAYS",
    configValue: "365",
    description: "Audit retention in days",
  },
] satisfies components["schemas"]["AuditConfigVO"][];

const auditPath = "*/record-platform/api/v1/system/audit";
const configRequests: string[] = [];
const configRead = vi.fn();
const anomalyCheck = vi.fn();
const backup = vi.fn();
let configs = safeConfigs.map((config) => ({ ...config }));
let configsUnavailable = false;

const server = setupServer(
  http.get(`${auditPath}/logs`, () =>
    HttpResponse.json({
      code: 200,
      data: { records: [], total: 0, current: 1, size: 20, pages: 0 },
    }),
  ),
  http.all(`${auditPath}/configs`, ({ request }) => {
    configRequests.push(request.method);
    if (request.method !== "GET" || configsUnavailable) {
      return HttpResponse.json(
        { code: 70002, message: "Audit settings are unavailable" },
        { status: 403 },
      );
    }
    configRead({
      authorization: request.headers.get("Authorization"),
      tenantId: request.headers.get("X-Tenant-ID"),
    });
    return HttpResponse.json({ code: 200, data: configs });
  }),
  http.get(`${auditPath}/overview`, () =>
    HttpResponse.json({ code: 200, data: { dailyStats: [] } }),
  ),
  ...["high-frequency", "error-stats", "time-distribution"].map((path) =>
    http.get(`${auditPath}/${path}`, () =>
      HttpResponse.json({ code: 200, data: [] }),
    ),
  ),
  http.post(`${auditPath}/anomalies/check`, () => {
    anomalyCheck();
    return HttpResponse.json({
      code: 200,
      data: { highFrequencyCount: 0 },
    });
  }),
  http.post(`${auditPath}/logs/backups`, ({ request }) => {
    const params = new URL(request.url).searchParams;
    backup({
      days: params.get("days"),
      deleteAfterBackup: params.get("deleteAfterBackup"),
    });
    return HttpResponse.json({ code: 200, data: "backup complete" });
  }),
);

// Install interception before importing the real client and route graph.
vi.stubGlobal("AbortController", transferableAbortController().constructor);
server.listen({ onUnhandledRequest: "error" });
const [{ default: AuditPage }, client] = await Promise.all([
  import("../../+page.svelte"),
  import("$api/client"),
]);

/** Opens settings through the actual audit page and its real drawer. */
async function openSettings(): Promise<HTMLElement> {
  render(AuditPage);
  await fireEvent.click(screen.getByRole("button", { name: "设置" }));
  return screen.findByRole("dialog", { name: "审计设置" });
}

/** Verifies the tenant configuration section has no global mutation controls. */
function expectReadOnlySettings(dialog: HTMLElement): void {
  const settings = within(dialog);
  expect(settings.getByText(/全局配置仅供查看/)).toBeInTheDocument();
  expect(
    settings.queryByRole("columnheader", { name: "操作" }),
  ).not.toBeInTheDocument();
  expect(
    settings.queryByRole("button", { name: /编辑|保存/ }),
  ).not.toBeInTheDocument();
  expect(
    dialog.querySelector('input, textarea, select, [contenteditable="true"]'),
  ).toBeNull();
}

describe("tenant audit read-only configuration", () => {
  beforeEach(() => {
    auth.role = "admin";
    configs = safeConfigs.map((config) => ({ ...config }));
    configsUnavailable = false;
    configRequests.length = 0;
    client.setToken("tenant-audit-session", "2099-01-01", false);
  });

  afterEach(() => {
    cleanup();
    server.resetHandlers();
  });

  afterAll(() => {
    server.close();
    vi.unstubAllGlobals();
  });

  it.each(["admin", "monitor"])(
    "displays and refreshes the safe projection for tenant %s without writes",
    async (role) => {
      auth.role = role;
      const dialog = await openSettings();
      await within(dialog).findByText("LOG_RETENTION_DAYS");

      expect(within(dialog).getAllByRole("row")).toHaveLength(5);
      for (const config of safeConfigs) {
        const row = within(dialog).getByRole("row", {
          name: new RegExp(config.configKey),
        });
        expect(row).toHaveTextContent(config.configValue);
        expect(row).toHaveTextContent(config.description);
      }
      expectReadOnlySettings(dialog);
      expect(configRead).toHaveBeenCalledExactlyOnceWith({
        authorization: "Bearer tenant-audit-session",
        tenantId: "1",
      });

      configs = configs.map((config) =>
        config.configKey === "LOG_RETENTION_DAYS"
          ? { ...config, configValue: "180" }
          : config,
      );
      await fireEvent.click(
        within(dialog).getByRole("button", { name: "刷新" }),
      );
      await within(dialog).findByText("180");

      expectReadOnlySettings(dialog);
      expect(configRequests).toEqual(["GET", "GET"]);
      expect(configRead).toHaveBeenCalledTimes(2);
      expect(notifications.error).not.toHaveBeenCalled();
      expect(goto).not.toHaveBeenCalled();
    },
  );

  it("keeps an empty safe projection read-only", async () => {
    configs = [];
    const dialog = await openSettings();
    await within(dialog).findByText("暂无配置");

    expectReadOnlySettings(dialog);
    expect(configRequests).toEqual(["GET"]);
    expect(within(dialog).getByRole("button", { name: "刷新" })).toBeEnabled();
  });

  it("retains the read-only boundary after a failed load and successful retry", async () => {
    configsUnavailable = true;
    const dialog = await openSettings();
    await waitFor(() =>
      expect(notifications.error).toHaveBeenCalledWith(
        "加载配置失败",
        "Audit settings are unavailable",
      ),
    );
    expectReadOnlySettings(dialog);

    configsUnavailable = false;
    await fireEvent.click(within(dialog).getByRole("button", { name: "刷新" }));
    await within(dialog).findByText("LOG_RETENTION_DAYS");

    expectReadOnlySettings(dialog);
    expect(configRequests).toEqual(["GET", "GET"]);
    expect(client.getToken()).toBe("tenant-audit-session");
  });

  it("preserves anomaly checks and backup options without configuration writes", async () => {
    const dialog = await openSettings();
    await within(dialog).findByText("LOG_RETENTION_DAYS");
    const settings = within(dialog);

    await fireEvent.click(settings.getByRole("button", { name: "异常检查" }));
    await fireEvent.click(settings.getByRole("button", { name: "执行检查" }));
    await settings.findByText(/"highFrequencyCount": 0/);
    expect(anomalyCheck).toHaveBeenCalledOnce();
    await fireEvent.click(settings.getByRole("button", { name: "清空结果" }));
    expect(
      settings.queryByText(/"highFrequencyCount"/),
    ).not.toBeInTheDocument();

    await fireEvent.click(settings.getByRole("button", { name: "日志备份" }));
    await fireEvent.input(settings.getByLabelText("备份天数"), {
      target: { value: "7" },
    });
    await fireEvent.click(
      settings.getByRole("checkbox", { name: "备份后删除原日志" }),
    );
    await fireEvent.click(settings.getByRole("button", { name: "开始备份" }));
    await settings.findByText("backup complete");
    expect(backup).toHaveBeenCalledExactlyOnceWith({
      days: "7",
      deleteAfterBackup: "true",
    });
    await fireEvent.click(settings.getByRole("button", { name: "清空结果" }));
    expect(settings.queryByText("backup complete")).not.toBeInTheDocument();

    await fireEvent.click(settings.getByRole("button", { name: "审计配置" }));
    expectReadOnlySettings(dialog);
    expect(configRequests).toEqual(["GET"]);
    expect(notifications.error).not.toHaveBeenCalled();
  });
});
