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

import * as friendsApi from "./friends";

/**
 * 执行一组好友相关接口调用，集中覆盖路径拼装。
 */
async function callAllFriendApis(): Promise<void> {
  await friendsApi.sendFriendRequest({ addresseeId: "u2", message: "hi" });
  await friendsApi.getReceivedRequests({ pageNum: 1 });
  await friendsApi.getSentRequests({ pageNum: 1 });
  await friendsApi.acceptFriendRequest("req-1");
  await friendsApi.rejectFriendRequest("req-1");
  await friendsApi.cancelFriendRequest("req-1");
  await friendsApi.getPendingRequestCount();
  await friendsApi.getFriends({ pageNum: 1, pageSize: 10 });
  await friendsApi.getAllFriends();
  await friendsApi.unfriend("friend-1");
  await friendsApi.updateFriendRemark("friend-1", { remark: "备注" });
  await friendsApi.searchUsers("alice");
  await friendsApi.shareToFriend({ friendId: "f1", fileHashes: ["file-1"] });
  await friendsApi.getReceivedFriendShares({ pageNum: 1 });
  await friendsApi.getSentFriendShares({ pageNum: 1 });
  await friendsApi.getFriendShareDetail("share-1");
  await friendsApi.markFriendShareAsRead("share-1");
  await friendsApi.cancelFriendShare("share-1");
  await friendsApi.getUnreadFriendShareCount();
}

describe("friends endpoints", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clientMocks.api.get.mockResolvedValue({});
    clientMocks.api.post.mockResolvedValue({});
    clientMocks.api.put.mockResolvedValue({});
    clientMocks.api.delete.mockResolvedValue({});
  });

  it("应覆盖好友与好友分享模块全部导出函数路径", async () => {
    await callAllFriendApis();

    expect(clientMocks.api.post).toHaveBeenNthCalledWith(1, "/friends/requests", {
      addresseeId: "u2",
      message: "hi",
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      1,
      "/friends/requests/received",
      { params: { pageNum: 1 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      2,
      "/friends/requests/sent",
      { params: { pageNum: 1 } },
    );
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      1,
      "/friends/requests/req-1/status",
      null,
      { params: { status: "accept" } },
    );
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      2,
      "/friends/requests/req-1/status",
      null,
      { params: { status: "reject" } },
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      1,
      "/friends/requests/req-1",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      3,
      "/friends/requests/pending-count",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(4, "/friends", {
      params: { pageNum: 1, pageSize: 10 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(5, "/friends/all");
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(2, "/friends/friend-1");
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      3,
      "/friends/friend-1/remark",
      { remark: "备注" },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(6, "/friends/search", {
      params: { keyword: "alice" },
    });
    expect(clientMocks.api.post).toHaveBeenNthCalledWith(
      2,
      "/friend-shares",
      { friendId: "f1", fileHashes: ["file-1"] },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      7,
      "/friend-shares/received",
      { params: { pageNum: 1 } },
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(8, "/friend-shares/sent", {
      params: { pageNum: 1 },
    });
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(9, "/friend-shares/share-1");
    expect(clientMocks.api.put).toHaveBeenNthCalledWith(
      4,
      "/friend-shares/share-1/read-status",
    );
    expect(clientMocks.api.delete).toHaveBeenNthCalledWith(
      3,
      "/friend-shares/share-1",
    );
    expect(clientMocks.api.get).toHaveBeenNthCalledWith(
      10,
      "/friend-shares/unread-count",
    );
  });
});
