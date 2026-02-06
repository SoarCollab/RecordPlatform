import { describe, expect, it, vi } from "vitest";
import { handleSseMessage, type BadgeController, type NotificationController } from "./sseMessageHandler";

/**
 * 创建可观测的 badges mock，便于断言计数更新行为。
 *
 * @returns badges 控制器 mock 对象。
 */
function createBadges(): BadgeController {
  return {
    unreadMessages: 1,
    unreadAnnouncements: 2,
    pendingTickets: 3,
    pendingFriendRequests: 4,
    unreadFriendShares: 5,
    friendBadgeTotal: 9,
    updateMessageCount: vi.fn(),
    updateAnnouncementCount: vi.fn(),
    updateTicketCount: vi.fn(),
    updateFriendRequestCount: vi.fn(),
    updateFriendShareCount: vi.fn(),
    fetch: vi.fn(),
  };
}

/**
 * 创建通知 mock，统一断言不同通知类型分发。
 *
 * @returns 通知控制器 mock 对象。
 */
function createNotifications(): NotificationController {
  return {
    info: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
  };
}

describe("sseMessageHandler", () => {
  it("message-received: 非消息页应更新计数并提示", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "message-received",
        data: { senderName: "alice", content: "hello" },
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(badges.updateMessageCount).toHaveBeenCalledWith(2);
    expect(notifications.info).toHaveBeenCalledWith("来自 alice 的新消息", "hello");
  });

  it("message-received: 消息页应抑制通知", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "message-received",
        data: {},
        timestamp: "t",
      },
      { pathname: "/messages/123", badges, notifications },
    );

    expect(badges.updateMessageCount).toHaveBeenCalledWith(2);
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it("announcement-published 应更新公告计数并通知", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "announcement-published",
        data: { title: "新功能" },
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(badges.updateAnnouncementCount).toHaveBeenCalledWith(3);
    expect(notifications.info).toHaveBeenCalledWith("新公告", "新功能");
  });

  it("ticket-updated 应覆盖回复/状态/默认三类通知分支", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "ticket-updated",
        data: { ticketNo: "1001", replierName: "Bob", preview: "已处理" },
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    handleSseMessage(
      {
        type: "ticket-updated",
        data: { ticketNo: "1002", oldStatus: "open", newStatus: "closed" },
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    handleSseMessage(
      {
        type: "ticket-updated",
        data: {},
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(badges.fetch).toHaveBeenCalledTimes(3);
    expect(notifications.info).toHaveBeenCalledWith("工单新回复", "#1001 Bob: 已处理");
    expect(notifications.info).toHaveBeenCalledWith(
      "工单状态更新",
      "#1002 open → closed",
    );
    expect(notifications.info).toHaveBeenCalledWith("工单更新", "你的工单有新的动态");
  });

  it("ticket-updated: 工单页应抑制通知", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "ticket-updated",
        data: { replierName: "Bob" },
        timestamp: "t",
      },
      { pathname: "/tickets/1", badges, notifications },
    );

    expect(notifications.info).not.toHaveBeenCalled();
    expect(badges.fetch).toHaveBeenCalledTimes(1);
  });

  it("file-processed 应按状态分发成功/失败通知", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "file-processed", data: { fileName: "a.pdf", status: "completed" }, timestamp: "t" },
      { pathname: "/files", badges, notifications },
    );
    handleSseMessage(
      { type: "file-processed", data: { fileName: "b.pdf", status: "failed" }, timestamp: "t" },
      { pathname: "/files", badges, notifications },
    );

    expect(notifications.success).toHaveBeenCalledWith("文件处理完成", "a.pdf");
    expect(notifications.error).toHaveBeenCalledWith("文件处理失败", "b.pdf");
  });

  it("badge-update 应按字段选择性更新", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      {
        type: "badge-update",
        data: { messages: 10, announcements: 11, tickets: 12 },
        timestamp: "t",
      },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(badges.updateMessageCount).toHaveBeenCalledWith(10);
    expect(badges.updateAnnouncementCount).toHaveBeenCalledWith(11);
    expect(badges.updateTicketCount).toHaveBeenCalledWith(12);
  });

  it("notification 应按 type 分发到对应通知级别", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "notification", data: { title: "t1", message: "m1", type: "error" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "notification", data: { title: "t2", message: "m2", type: "warning" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "notification", data: { title: "t3", message: "m3", type: "success" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "notification", data: { title: "t4", message: "m4", type: "unknown" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(notifications.error).toHaveBeenCalledWith("t1", "m1");
    expect(notifications.warning).toHaveBeenCalledWith("t2", "m2");
    expect(notifications.success).toHaveBeenCalledWith("t3", "m3");
    expect(notifications.info).toHaveBeenCalledWith("t4", "m4");
  });

  it("friend-request/friend-accepted/friend-share 应更新好友相关状态", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "friend-request", data: { requesterName: "Alice" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    handleSseMessage(
      { type: "friend-accepted", data: { friendName: "Bob" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    handleSseMessage(
      { type: "friend-share", data: { sharerName: "Cindy", fileCount: 2 }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(badges.updateFriendRequestCount).toHaveBeenCalledWith(5);
    expect(badges.fetch).toHaveBeenCalledTimes(1);
    expect(badges.updateFriendShareCount).toHaveBeenCalledWith(6);
    expect(notifications.info).toHaveBeenCalledWith("新好友请求", "Alice 请求添加你为好友");
    expect(notifications.success).toHaveBeenCalledWith("好友添加成功", "Bob 已接受你的好友请求");
    expect(notifications.info).toHaveBeenCalledWith("好友分享", "Cindy 分享了 2 个文件给你");
  });

  it("friend-request/friend-share 在 friends 页面应抑制通知", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "friend-request", data: {}, timestamp: "t" },
      { pathname: "/friends/requests", badges, notifications },
    );
    handleSseMessage(
      { type: "friend-share", data: {}, timestamp: "t" },
      { pathname: "/friends/shares", badges, notifications },
    );

    expect(notifications.info).not.toHaveBeenCalled();
  });
});

describe("sseMessageHandler fallback message", () => {
  it("好友相关消息缺少名称字段时应走默认文案", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "friend-request", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "friend-accepted", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "friend-share", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(notifications.info).toHaveBeenCalledWith("新好友请求", "收到新的好友请求");
    expect(notifications.success).toHaveBeenCalledWith("好友添加成功", "你们已成为好友");
    expect(notifications.info).toHaveBeenCalledWith("好友分享", "收到好友分享的文件");
  });
});

describe("sseMessageHandler branch fill", () => {
  it("应覆盖消息/公告/工单/通知的默认分支文案", () => {
    const badges = createBadges();
    const notifications = createNotifications();

    handleSseMessage(
      { type: "message-received", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "announcement-published", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "ticket-updated", data: { replierName: "A" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "ticket-updated", data: { newStatus: "closed" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "file-processed", data: { status: "completed" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "file-processed", data: { status: "failed" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "notification", data: {}, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );
    handleSseMessage(
      { type: "friend-share", data: { sharerName: "D" }, timestamp: "t" },
      { pathname: "/dashboard", badges, notifications },
    );

    expect(notifications.info).toHaveBeenCalledWith("收到新消息", "点击查看");
    expect(notifications.info).toHaveBeenCalledWith("新公告", "系统发布了新公告");
    expect(notifications.info).toHaveBeenCalledWith("工单新回复", "A: 收到新的回复");
    expect(notifications.info).toHaveBeenCalledWith("工单状态更新", "closed");
    expect(notifications.success).toHaveBeenCalledWith("文件处理完成", "您的文件已处理完毕");
    expect(notifications.error).toHaveBeenCalledWith("文件处理失败", "文件处理过程中出错");
    expect(notifications.info).toHaveBeenCalledWith("通知", "");
    expect(notifications.info).toHaveBeenCalledWith("好友分享", "D 分享了 1 个文件给你");
  });
});
