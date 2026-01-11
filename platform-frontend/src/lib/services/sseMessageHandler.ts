import type { SSEMessage } from "$api/endpoints/sse";

export interface BadgeController {
  unreadMessages: number;
  unreadAnnouncements: number;
  pendingTickets: number;
  pendingFriendRequests: number;
  unreadFriendShares: number;
  friendBadgeTotal: number;

  updateMessageCount(count: number): void;
  updateAnnouncementCount(count: number): void;
  updateTicketCount(count: number): void;
  updateFriendRequestCount(count: number): void;
  updateFriendShareCount(count: number): void;
  fetch(): void;
}

export interface NotificationController {
  info(title: string, message?: string): void;
  success(title: string, message?: string): void;
  warning(title: string, message?: string): void;
  error(title: string, message?: string): void;
}

export interface SseMessageHandlerContext {
  pathname: string;
  badges: BadgeController;
  notifications: NotificationController;
}

export function handleSseMessage(
  message: SSEMessage,
  ctx: SseMessageHandlerContext,
): void {
  const { pathname, badges, notifications } = ctx;

  switch (message.type) {
    case "message-received": {
      const data = message.data as { senderName?: string; content?: string };
      badges.updateMessageCount(badges.unreadMessages + 1);
      if (!pathname.startsWith("/messages")) {
        notifications.info(
          data.senderName ? `来自 ${data.senderName} 的新消息` : "收到新消息",
          data.content || "点击查看",
        );
      }
      break;
    }
    case "announcement-published": {
      const data = message.data as { title?: string };
      badges.updateAnnouncementCount(badges.unreadAnnouncements + 1);
      notifications.info("新公告", data.title || "系统发布了新公告");
      break;
    }
    case "ticket-updated": {
      const data = message.data as {
        ticketId?: string;
        ticketNo?: string;
        replierName?: string;
        preview?: string;
        oldStatus?: string;
        newStatus?: string;
      };
      badges.fetch();
      if (!pathname.startsWith("/tickets")) {
        if (data.replierName) {
          notifications.info(
            "工单新回复",
            `${data.ticketNo ? `#${data.ticketNo} ` : ""}${data.replierName}: ${data.preview || "收到新的回复"}`,
          );
        } else if (data.newStatus) {
          notifications.info(
            "工单状态更新",
            `${data.ticketNo ? `#${data.ticketNo} ` : ""}${data.oldStatus || ""}${data.oldStatus ? " → " : ""}${data.newStatus}`,
          );
        } else {
          notifications.info("工单更新", "你的工单有新的动态");
        }
      }
      break;
    }
    case "file-processed": {
      const data = message.data as { fileName?: string; status?: string };
      if (data.status === "completed") {
        notifications.success(
          "文件处理完成",
          data.fileName || "您的文件已处理完毕",
        );
      } else if (data.status === "failed") {
        notifications.error(
          "文件处理失败",
          data.fileName || "文件处理过程中出错",
        );
      }
      break;
    }
    case "badge-update": {
      const data = message.data as {
        messages?: number;
        announcements?: number;
        tickets?: number;
      };
      if (typeof data.messages === "number")
        badges.updateMessageCount(data.messages);
      if (typeof data.announcements === "number")
        badges.updateAnnouncementCount(data.announcements);
      if (typeof data.tickets === "number")
        badges.updateTicketCount(data.tickets);
      break;
    }
    case "notification": {
      const data = message.data as {
        title?: string;
        message?: string;
        type?: string;
      };
      const title = data.title || "通知";
      const content = data.message || "";
      if (data.type === "error") {
        notifications.error(title, content);
      } else if (data.type === "warning") {
        notifications.warning(title, content);
      } else if (data.type === "success") {
        notifications.success(title, content);
      } else {
        notifications.info(title, content);
      }
      break;
    }
    case "friend-request": {
      const data = message.data as { requesterName?: string };
      badges.updateFriendRequestCount(badges.pendingFriendRequests + 1);
      if (!pathname.startsWith("/friends")) {
        notifications.info(
          "新好友请求",
          data.requesterName
            ? `${data.requesterName} 请求添加你为好友`
            : "收到新的好友请求",
        );
      }
      break;
    }
    case "friend-accepted": {
      const data = message.data as { friendName?: string };
      badges.fetch();
      notifications.success(
        "好友添加成功",
        data.friendName
          ? `${data.friendName} 已接受你的好友请求`
          : "你们已成为好友",
      );
      break;
    }
    case "friend-share": {
      const data = message.data as { sharerName?: string; fileCount?: number };
      badges.updateFriendShareCount(badges.unreadFriendShares + 1);
      if (!pathname.startsWith("/friends")) {
        notifications.info(
          "好友分享",
          data.sharerName
            ? `${data.sharerName} 分享了 ${data.fileCount || 1} 个文件给你`
            : "收到好友分享的文件",
        );
      }
      break;
    }
  }
}
