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
    getToken: vi.fn(),
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
  getToken: clientMocks.getToken,
}));

import * as systemApi from "./system";

/**
 * 断言某个 API 调用是否命中了指定路径。
 *
 * @param call 被断言的 mock 调用。
 * @param path 目标路径。
 */
function expectCallPath(call: unknown[], path: string): void {
  expect(call[0]).toBe(path);
}

describe("system endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("系统监控类查询接口应命中正确路径", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({});

    await systemApi.getSystemStats();
    await systemApi.getChainStatus();
    await systemApi.getSystemHealth();
    await systemApi.getMonitorMetrics();

    const calls = clientMocks.api.get.mock.calls;
    expectCallPath(calls[0], "/system/stats");
    expectCallPath(calls[1], "/system/chain-status");
    expectCallPath(calls[2], "/system/health");
    expectCallPath(calls[3], "/system/monitor");
  });

  it("权限接口应透传路径和参数", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce(["p:read"]);
    clientMocks.api.post.mockResolvedValue(undefined);
    clientMocks.api.put.mockResolvedValue({});
    clientMocks.api.delete.mockResolvedValue(undefined);

    await systemApi.getPermissionTree();
    await systemApi.listPermissionModules();
    await systemApi.listPermissions({ module: "files", pageNum: 1, pageSize: 10 });
    await systemApi.createPermission({ code: "perm:1", name: "perm-1", module: "files", action: "read" });
    await systemApi.updatePermission("id-1", { name: "new-name" });
    await systemApi.deletePermission("id-2");
    await systemApi.getRolePermissions("admin");
    await systemApi.grantRolePermission("admin", { permissionCode: "p:read" });
    await systemApi.revokeRolePermission("admin", "p:read");

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/system/permissions");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/system/permissions/modules");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      3,
      "/system/permissions/list",
      { params: { module: "files", pageNum: 1, pageSize: 10 } },
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/system/permissions",
      { code: "perm:1", name: "perm-1", module: "files", action: "read" },
    );
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      1,
      "/system/permissions/id-1",
      { name: "new-name" },
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      1,
      "/system/permissions/id-2",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      4,
      "/system/permissions/roles/admin",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/system/permissions/roles/admin/grant",
      { permissionCode: "p:read" },
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      2,
      "/system/permissions/roles/admin/revoke",
      { params: { permissionCode: "p:read" } },
    );
  });

  it("审计接口应透传路径和参数", async () => {
    clientMocks.api.get
      .mockResolvedValueOnce({ records: [], total: 0 })
      .mockResolvedValueOnce({ id: "log-1" })
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce({});
    clientMocks.api.post.mockResolvedValueOnce({ records: [], total: 0 }).mockResolvedValueOnce("backup-id");
    clientMocks.api.put.mockResolvedValue(true);

    await systemApi.getAuditLogs({ pageNum: 1, pageSize: 20, module: "files" });
    await systemApi.getAuditLog("log-1");
    await systemApi.getAuditOverview();
    await systemApi.getHighFrequencyOperations();
    await systemApi.getSensitiveOperations({ pageNum: 1, pageSize: 5 });
    await systemApi.getErrorOperationStats();
    await systemApi.getUserTimeDistribution();
    await systemApi.getAuditConfigs();
    await systemApi.updateAuditConfig({ id: 1, configKey: "k", configValue: "v" });
    await systemApi.checkAuditAnomalies();
    await systemApi.backupAuditLogs({ days: 7, deleteAfterBackup: false });

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/system/audit/logs/page", {
      params: { pageNum: 1, pageSize: 20, module: "files" },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/system/audit/logs/log-1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/system/audit/overview");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/system/audit/high-frequency");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      1,
      "/system/audit/sensitive/page",
      { pageNum: 1, pageSize: 5 },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(5, "/system/audit/error-stats");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(6, "/system/audit/time-distribution");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(7, "/system/audit/configs");
    expect(clientMocks.api.put).toHaveBeenCalledWith(
      "/system/audit/configs",
      { id: 1, configKey: "k", configValue: "v" },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(8, "/system/audit/check-anomalies");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/system/audit/backup-logs",
      null,
      { params: { days: 7, deleteAfterBackup: false } },
    );
  });

  it("exportAuditLogs: 未登录时应直接抛错", async () => {
    clientMocks.getToken.mockReturnValue(null);
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    await expect(systemApi.exportAuditLogs({})).rejects.toThrow("未登录");
    expect(fetchSpy).not.toHaveBeenCalled();

    fetchSpy.mockRestore();
  });

  it("exportAuditLogs: 导出失败时应抛错", async () => {
    clientMocks.getToken.mockReturnValue("jwt-token");
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response("failed", { status: 500 }));

    await expect(systemApi.exportAuditLogs({ username: "abc" })).rejects.toThrow(
      "导出失败",
    );

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0];
    expect(String(url)).toContain("/system/audit/logs/export");
    expect(init?.method).toBe("POST");
    expect((init?.headers as Record<string, string>).Authorization).toBe(
      "Bearer jwt-token",
    );

    fetchSpy.mockRestore();
  });

  it("exportAuditLogs: 成功时应返回 blob", async () => {
    clientMocks.getToken.mockReturnValue("jwt-token");
    const expectedBlob = new Blob(["ok"], { type: "application/json" });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      ok: true,
      blob: vi.fn().mockResolvedValue(expectedBlob),
    } as unknown as Response);

    const result = await systemApi.exportAuditLogs({ username: "u", status: 1 });

    expect(result).toBe(expectedBlob);
    const [, init] = fetchSpy.mock.calls[0];
    expect(JSON.parse(String(init?.body))).toEqual({ username: "u", status: 1 });

    fetchSpy.mockRestore();
  });
});
