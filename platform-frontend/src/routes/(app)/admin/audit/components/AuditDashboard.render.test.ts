import { cleanup, fireEvent, render, waitFor } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  getAuditOverview: vi.fn(),
  getHighFrequencyOperations: vi.fn(),
  getErrorOperationStats: vi.fn(),
  getUserTimeDistribution: vi.fn(),
  getAuditLogs: vi.fn(),
  getAuditLog: vi.fn(),
}));

vi.mock("$api/endpoints/system", () => apiMocks);

import AuditDashboard from "./AuditDashboard.svelte";
import AuditLogList from "./AuditLogList.svelte";

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
});
