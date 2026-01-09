export interface ConversationVO {
  id: string;
  otherUserId: string;
  otherUsername: string;
  otherAvatar?: string;
  lastMessageContent?: string;
  lastMessageType?: string;
  lastMessageTime?: string;
  unreadCount: number;
  otherNickname?: string;
  createTime?: string;
}

export interface MessageVO {
  id: string;
  senderId: string;
  senderUsername?: string;
  senderAvatar?: string;
  content: string;
  contentType: string;
  isMine: boolean;
  isRead: boolean;
  createTime: string;
}

export interface ConversationDetailVO {
  id: string;
  otherUserId: string;
  otherUsername: string;
  otherAvatar?: string;
  messages: MessageVO[];
  hasMore: boolean;
  totalMessages: number;
}

export interface SendMessageRequest {
  receiverId: string;
  content: string;
  contentType?: string;
}

export interface AnnouncementVO {
  id: string;
  title: string;
  content: string;
  priority: AnnouncementPriority;
  priorityDesc?: string;
  pinned?: boolean;
  publishTime?: string;
  expireTime?: string;
  status: AnnouncementStatus;
  statusDesc?: string;
  publisherId?: string;
  author?: string;
  read?: boolean;
  createTime: string;
}

export enum AnnouncementPriority {
  NORMAL = 0,
  IMPORTANT = 1,
  URGENT = 2,
}

export enum AnnouncementStatus {
  DRAFT = 0,
  PUBLISHED = 1,
  EXPIRED = 2,
}

export const AnnouncementPriorityLabel: Record<AnnouncementPriority, string> = {
  [AnnouncementPriority.NORMAL]: "普通",
  [AnnouncementPriority.IMPORTANT]: "重要",
  [AnnouncementPriority.URGENT]: "紧急",
};
