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

import * as messageApi from "./messages";

/**
 * 依次调用消息中心所有导出函数，覆盖路径和参数分支。
 */
async function callAllMessageApis(): Promise<void> {
  await messageApi.getConversations({ pageNum: 1, pageSize: 20 });
  await messageApi.getConversationDetail("c1", { pageNum: 2, pageSize: 10 });
  await messageApi.getConversation("c2", { pageNum: 1, pageSize: 5 });
  await messageApi.deleteConversation("c3");
  await messageApi.markAsRead("c4");
  await messageApi.sendMessage({ receiverId: "u2", content: "hello" });
  await messageApi.sendMessage({
    receiverId: "u2",
    content: "rich",
    contentType: "markdown",
  });
  await messageApi.getUnreadMessageCount();
  await messageApi.getUnreadConversationCount();
  await messageApi.getAnnouncements({ pageNum: 1 });
  await messageApi.getAnnouncement("a1");
  await messageApi.getLatestAnnouncements(3);
  await messageApi.getUnreadAnnouncementCount();
  await messageApi.markAnnouncementAsRead("a2");
  await messageApi.markAllAnnouncementsAsRead();
}

describe("messages endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.get.mockResolvedValue({});
    clientMocks.api.post.mockResolvedValue({});
    clientMocks.api.delete.mockResolvedValue(undefined);
  });

  it("应覆盖消息、会话、公告的全部导出函数", async () => {
    await callAllMessageApis();

    expect(clientMocks.api.get).toHaveBeenNthCalledWith(1, "/conversations", {
      params: { pageNum: 1, pageSize: 20 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(2, "/conversations/c1", {
      params: { pageNum: 2, pageSize: 10 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(3, "/conversations/c2", {
      params: { pageNum: 1, pageSize: 5 },
    });
    expect(clientMocks.api.delete).toHaveBeenCalledWith("/conversations/c3");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(1, "/conversations/c4/read");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(2, "/messages", {
      receiverId: "u2",
      content: "hello",
      contentType: "text",
    });
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(3, "/messages", {
      receiverId: "u2",
      content: "rich",
      contentType: "markdown",
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/messages/unread-count");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      5,
      "/conversations/unread-count",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(6, "/announcements", {
      params: { pageNum: 1 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(7, "/announcements/a1");
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(8, "/announcements/latest", {
      params: { limit: 3 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      9,
      "/announcements/unread-count",
    );
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(4, "/announcements/a2/read");
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      5,
      "/announcements/read-all",
    );
  });
});
