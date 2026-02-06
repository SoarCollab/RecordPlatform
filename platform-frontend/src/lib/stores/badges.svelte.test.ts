import { beforeEach, describe, expect, it, vi } from "vitest";

const endpointMocks = vi.hoisted(() => {
  return {
    getUnreadConversationCount: vi.fn(),
    getUnreadAnnouncementCount: vi.fn(),
    getUnreadCount: vi.fn(),
    getPendingRequestCount: vi.fn(),
    getUnreadFriendShareCount: vi.fn(),
  };
});

vi.mock("$api/endpoints/messages", () => ({
  getUnreadConversationCount: endpointMocks.getUnreadConversationCount,
  getUnreadAnnouncementCount: endpointMocks.getUnreadAnnouncementCount,
}));

vi.mock("$api/endpoints/tickets", () => ({
  getUnreadCount: endpointMocks.getUnreadCount,
}));

vi.mock("$api/endpoints/friends", () => ({
  getPendingRequestCount: endpointMocks.getPendingRequestCount,
  getUnreadFriendShareCount: endpointMocks.getUnreadFriendShareCount,
}));

/**
 * 重新加载 badges store，隔离模块级状态。
 *
 * @returns badges store 实例。
 */
async function loadBadgesStore() {
  vi.resetModules();
  const mod = await import("./badges.svelte");
  return mod.useBadges();
}

describe("badges store", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    endpointMocks.getUnreadConversationCount.mockResolvedValue({ count: 1 });
    endpointMocks.getUnreadAnnouncementCount.mockResolvedValue({ count: 2 });
    endpointMocks.getUnreadCount.mockResolvedValue({ count: 3 });
    endpointMocks.getPendingRequestCount.mockResolvedValue({ count: 4 });
    endpointMocks.getUnreadFriendShareCount.mockResolvedValue({ count: 5 });
  });

  it("fetch 应更新所有徽章计数", async () => {
    const badges = await loadBadgesStore();

    await badges.fetch();

    expect(badges.unreadMessages).toBe(1);
    expect(badges.unreadAnnouncements).toBe(2);
    expect(badges.pendingTickets).toBe(3);
    expect(badges.pendingFriendRequests).toBe(4);
    expect(badges.unreadFriendShares).toBe(5);
    expect(badges.friendBadgeTotal).toBe(9);
    expect(badges.totalUnread).toBe(3);
    expect(badges.hasUnread).toBe(true);
    expect(badges.lastFetched).toBeInstanceOf(Date);
  });

  it("Promise.allSettled 局部失败时应保留已成功计数", async () => {
    endpointMocks.getUnreadConversationCount.mockResolvedValue({ count: 7 });
    endpointMocks.getUnreadAnnouncementCount.mockRejectedValue(new Error("fail"));
    endpointMocks.getUnreadCount.mockResolvedValue({ count: 9 });
    endpointMocks.getPendingRequestCount.mockRejectedValue(new Error("fail"));
    endpointMocks.getUnreadFriendShareCount.mockResolvedValue({ count: 11 });

    const badges = await loadBadgesStore();
    await badges.fetch();

    expect(badges.unreadMessages).toBe(7);
    expect(badges.pendingTickets).toBe(9);
    expect(badges.unreadFriendShares).toBe(11);
    expect(badges.isLoading).toBe(false);
  });

  it("更新与递减操作应正确工作且不降为负数", async () => {
    const badges = await loadBadgesStore();

    badges.updateMessageCount(2);
    badges.updateAnnouncementCount(1);
    badges.updateTicketCount(5);
    badges.updateFriendRequestCount(6);
    badges.updateFriendShareCount(7);

    expect(badges.unreadMessages).toBe(2);
    expect(badges.unreadAnnouncements).toBe(1);
    expect(badges.pendingTickets).toBe(5);
    expect(badges.pendingFriendRequests).toBe(6);
    expect(badges.unreadFriendShares).toBe(7);

    badges.decrementMessages();
    badges.decrementMessages();
    badges.decrementMessages();
    badges.decrementAnnouncements();
    badges.decrementAnnouncements();

    expect(badges.unreadMessages).toBe(0);
    expect(badges.unreadAnnouncements).toBe(0);
  });

  it("reset 应清空全部计数", async () => {
    const badges = await loadBadgesStore();

    badges.updateMessageCount(5);
    badges.updateAnnouncementCount(4);
    badges.updateTicketCount(3);
    badges.updateFriendRequestCount(2);
    badges.updateFriendShareCount(1);

    badges.reset();

    expect(badges.unreadMessages).toBe(0);
    expect(badges.unreadAnnouncements).toBe(0);
    expect(badges.pendingTickets).toBe(0);
    expect(badges.pendingFriendRequests).toBe(0);
    expect(badges.unreadFriendShares).toBe(0);
    expect(badges.lastFetched).toBeNull();
  });

  it("startAutoRefresh 应幂等，stopAutoRefresh 应停止定时任务", async () => {
    vi.useFakeTimers();

    try {
      const badges = await loadBadgesStore();
      badges.startAutoRefresh();
      badges.startAutoRefresh();

      expect(endpointMocks.getUnreadConversationCount).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
      expect(endpointMocks.getUnreadConversationCount).toHaveBeenCalledTimes(2);

      badges.stopAutoRefresh();
      await vi.advanceTimersByTimeAsync(5 * 60 * 1000);
      expect(endpointMocks.getUnreadConversationCount).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
