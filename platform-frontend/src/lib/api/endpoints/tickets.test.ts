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

import * as ticketApi from "./tickets";

/**
 * 执行一组工单接口调用，覆盖用户侧与管理员侧路径。
 */
async function callTicketApis(): Promise<void> {
  await ticketApi.getTickets({ pageNum: 1, pageSize: 10 });
  await ticketApi.getTicket("t1");
  await ticketApi.createTicket({ title: "title", content: "body" });
  await ticketApi.updateTicket("t1", { title: "new title" });
  await ticketApi.closeTicket("t1");
  await ticketApi.replyTicket({ ticketId: "t1", content: "reply" });
  await ticketApi.confirmTicket("t1");
  await ticketApi.getPendingCount();
  await ticketApi.getUnreadCount();
  await ticketApi.getAdminTickets({ pageNum: 1, pageSize: 10 });
  await ticketApi.assignTicket("t1", "u1");
  await ticketApi.updateTicketStatus("t1", 2);
  await ticketApi.getAdminPendingCount();
}

describe("tickets endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.get.mockResolvedValue({});
    clientMocks.api.post.mockResolvedValue({});
    clientMocks.api.put.mockResolvedValue({});
  });

  it("应覆盖工单模块全部导出函数路径", async () => {
    await callTicketApis();

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/tickets", {
      params: { pageNum: 1, pageSize: 10 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/tickets/t1");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(1, "/tickets", {
      title: "title",
      content: "body",
    });
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(1, "/tickets/t1", {
      title: "new title",
    });
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(2, "/tickets/t1/close");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(3, "/tickets/t1/reply", {
      content: "reply",
    });
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(4, "/tickets/t1/confirm");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/tickets/pending-count");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/tickets/unread-count");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(5, "/admin/tickets", {
      params: { pageNum: 1, pageSize: 10 },
    });
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      2,
      "/admin/tickets/t1/assignee",
      null,
      { params: { assigneeId: "u1" } },
    );
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      3,
      "/admin/tickets/t1/status",
      null,
      { params: { status: 2 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      6,
      "/admin/tickets/pending-count",
    );
  });

  it("getTicketReplies 应抛出未实现错误", async () => {
    await expect(ticketApi.getTicketReplies("t1", { pageNum: 1 })).rejects.toThrow(
      "后端未提供独立的回复列表接口",
    );
  });
});
