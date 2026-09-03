import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  getAuditOverview: vi.fn(),
  getHighFrequencyOperations: vi.fn(),
  getErrorOperationStats: vi.fn(),
  getUserTimeDistribution: vi.fn(),
  getAuditLogs: vi.fn(),
  getAuditLog: vi.fn(),
  getSensitiveOperations: vi.fn(),
}));

vi.mock("$api/endpoints/system", () => apiMocks);

import AuditDashboard from "./AuditDashboard.svelte";
import AuditLogList from "./AuditLogList.svelte";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => {
    resolve = resolver;
  });
  return { promise, resolve };
}

describe("AuditDashboard high-frequency drill", () => {
  const alert = {
    userId: "audit-user-id",
    username: "audit-user",
    requestIp: "198.51.100.19",
    operationCount: 101,
    startTime: "2026-09-02 10:00:00",
    endTime: "2026-09-02 10:04:59",
    timeSpanSeconds: 299,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    apiMocks.getAuditOverview.mockResolvedValue({
      highFrequencyAlerts: 1,
      dailyStats: [],
    });
    apiMocks.getHighFrequencyOperations.mockResolvedValue([alert]);
    apiMocks.getErrorOperationStats.mockResolvedValue([]);
    apiMocks.getUserTimeDistribution.mockResolvedValue([]);
    apiMocks.getAuditLogs.mockResolvedValue({ records: [], total: 0 });
    apiMocks.getSensitiveOperations.mockResolvedValue({
      records: [],
      total: 0,
    });
    vi.stubGlobal("scrollTo", vi.fn());
    Element.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    cleanup();
  });

  it("focuses the actual alert table and drills a row with its exact group window", async () => {
    const onDrillDown = vi.fn();
    const view = render(AuditDashboard, { onDrillDown });

    await waitFor(() => expect(view.getByText("audit-user")).toBeTruthy());

    const alertCard = view.getByText("高频告警").closest("button");
    expect(alertCard).not.toBeNull();
    await fireEvent.click(alertCard!);

    const alertSection = view.container.querySelector("#high-frequency-alerts");
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({
      behavior: "smooth",
      block: "start",
    });
    expect(document.activeElement).toBe(alertSection);
    expect(onDrillDown).not.toHaveBeenCalled();

    const alertRow = view.getByText("audit-user").closest("tr");
    expect(alertRow).not.toBeNull();
    await fireEvent.click(alertRow!);

    expect(onDrillDown).toHaveBeenCalledWith({
      userId: alert.userId,
      username: alert.username,
      requestIp: alert.requestIp,
      startTime: alert.startTime,
      endTime: alert.endTime,
    });
  });

  it("propagates the alert IP and exact window into the log query", async () => {
    const view = render(AuditLogList, {
      externalFilters: {
        userId: alert.userId,
        username: alert.username,
        requestIp: alert.requestIp,
        startTime: alert.startTime,
        endTime: alert.endTime,
        _counter: 1,
      },
    });

    await waitFor(() => {
      expect(apiMocks.getAuditLogs).toHaveBeenCalledWith({
        pageNum: 1,
        pageSize: 20,
        userId: alert.userId,
        username: alert.username,
        requestIp: alert.requestIp,
        startTime: alert.startTime,
        endTime: alert.endTime,
      });
    });

    expect(
      (view.getByPlaceholderText("用户 ID") as HTMLInputElement).value,
    ).toBe(alert.userId);
    expect(
      (view.getByPlaceholderText("请求 IP") as HTMLInputElement).value,
    ).toBe(alert.requestIp);
  });

  it("uses server-side pagination when sensitive mode is selected", async () => {
    apiMocks.getSensitiveOperations.mockResolvedValue({
      records: [
        {
          id: "ext-sensitive-1",
          module: "权限管理",
          operationType: "授权",
          requestIp: "10.1.0.2",
          status: 0,
          userId: "user-1",
          username: "tester",
          operationTime: "2026-09-03 09:45:50",
          executionTime: 9,
        },
      ],
      total: 21,
    });
    const view = render(AuditLogList);

    await waitFor(() => expect(apiMocks.getAuditLogs).toHaveBeenCalled());
    await fireEvent.click(view.getByRole("button", { name: "敏感" }));

    await waitFor(() => {
      expect(apiMocks.getSensitiveOperations).toHaveBeenCalledWith({
        pageNum: 1,
        pageSize: 20,
      });
    });
    expect(view.getAllByText("授权")).toHaveLength(2);
    expect(view.getByText(/共 21 条/)).toBeTruthy();
    expect(apiMocks.getAuditLogs).toHaveBeenCalledTimes(1);
  });

  it("ignores an older ordinary-page response after switching to sensitive mode", async () => {
    const ordinaryRequest = deferred<{
      records: Array<{
        id: string;
        userId: string;
        username: string;
        action: string;
        module: string;
        ip: string;
        status: number;
        duration: number;
        createTime: string;
      }>;
      total: number;
    }>();
    apiMocks.getAuditLogs.mockReturnValueOnce(ordinaryRequest.promise);
    apiMocks.getSensitiveOperations.mockResolvedValueOnce({
      records: [
        {
          id: "ext-sensitive-latest",
          module: "权限管理",
          operationType: "删除",
          requestIp: "10.1.0.2",
          status: 0,
          userId: "user-1",
          username: "sensitive-latest",
          operationTime: "2026-09-03 09:45:50",
          executionTime: 9,
        },
      ],
      total: 1,
    });

    const view = render(AuditLogList);
    await waitFor(() => expect(apiMocks.getAuditLogs).toHaveBeenCalledOnce());
    await fireEvent.click(view.getByRole("button", { name: "敏感" }));
    await waitFor(() =>
      expect(view.getByText("sensitive-latest")).toBeTruthy(),
    );

    ordinaryRequest.resolve({
      records: [
        {
          id: "ext-ordinary-stale",
          userId: "user-2",
          username: "ordinary-stale",
          action: "查询",
          module: "系统审计",
          ip: "10.1.0.3",
          status: 0,
          duration: 3,
          createTime: "2026-09-03 09:45:49",
        },
      ],
      total: 1,
    });

    await waitFor(() => {
      expect(view.queryByText("ordinary-stale")).toBeNull();
      expect(view.getByText("sensitive-latest")).toBeTruthy();
    });
  });
});
