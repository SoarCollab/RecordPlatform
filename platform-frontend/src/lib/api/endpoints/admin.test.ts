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
  };
});

vi.mock("../client", () => ({
  api: clientMocks.api,
}));

import * as adminApi from "./admin";

/**
 * 调用后台文件审计模块全部导出函数。
 */
async function callAllAdminApis(): Promise<void> {
  await adminApi.getAllFiles({ pageNum: 1, pageSize: 10 });
  await adminApi.getFileDetail("f1");
  await adminApi.updateFileStatus("f1", { status: 1 });
  await adminApi.forceDeleteFile("f1", "违规文件");
  await adminApi.getAllShares({ pageNum: 1, pageSize: 10 });
  await adminApi.forceCancelShare("code-1", "违规分享");
  await adminApi.getShareAccessLogs("code-1", { pageNum: 2 });
  await adminApi.getShareAccessStats("code-1");
}

describe("admin endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.get.mockResolvedValue({});
    clientMocks.api.put.mockResolvedValue({});
    clientMocks.api.delete.mockResolvedValue({});
  });

  it("应覆盖管理员文件与分享管理所有接口路径", async () => {
    await callAllAdminApis();

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/admin/files", {
      params: { pageNum: 1, pageSize: 10 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/admin/files/f1");
    expect(clientMocks.api.put).toHaveBeenCalledWith(
      "/admin/files/f1/status",
      { status: 1 },
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(1, "/admin/files/f1", {
      params: { reason: "违规文件" },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/admin/files/shares", {
      params: { pageNum: 1, pageSize: 10 },
    });
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      2,
      "/admin/files/shares/code-1",
      { params: { reason: "违规分享" } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      4,
      "/admin/files/shares/code-1/logs",
      { params: { pageNum: 2 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      5,
      "/admin/files/shares/code-1/stats",
    );
  });
});
