/**
 * 会话信息
 * @see ConversationVO.java
 * @note 部分字段为前端扩展，后端可能不返回
 */
export interface ConversationVO {
  id: string;
  otherUserId: string;
  otherUsername: string;
  otherAvatar?: string;
  lastMessageContent?: string;
  lastMessageType?: string;
  lastMessageTime?: string;
  unreadCount: number;
  // 以下字段为前端扩展（后端 ConversationVO 不包含）
  otherNickname?: string;
  createTime?: string;
}

/**
 * 消息信息
 * @see MessageVO.java
 */
export interface MessageVO {
  id: string;
  conversationId: string;
  senderId: string;
  senderUsername: string;
  senderNickname?: string;
  receiverId: string;
  content: string;
  type: MessageType;
  status: MessageStatus;
  createTime: string;
}

/**
 * 消息类型
 */
export enum MessageType {
  TEXT = 0,
  IMAGE = 1,
  FILE = 2,
  SYSTEM = 99,
}

/**
 * 消息状态
 */
export enum MessageStatus {
  SENDING = 0,
  SENT = 1,
  DELIVERED = 2,
  READ = 3,
  FAILED = -1,
}

/**
 * 发送消息请求
 */
export interface SendMessageRequest {
  receiverUsername: string;
  content: string;
  type?: MessageType;
}

/**
 * 公告信息
 * @see AnnouncementVO.java
 */
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

/**
 * 公告优先级
 * @see MessagePriority.java
 */
export enum AnnouncementPriority {
  NORMAL = 0,
  IMPORTANT = 1,
  URGENT = 2,
}

/**
 * 公告状态
 * @see AnnouncementStatus.java
 */
export enum AnnouncementStatus {
  DRAFT = 0,
  PUBLISHED = 1,
  EXPIRED = 2,
}

/**
 * 公告优先级标签
 */
export const AnnouncementPriorityLabel: Record<AnnouncementPriority, string> = {
  [AnnouncementPriority.NORMAL]: "普通",
  [AnnouncementPriority.IMPORTANT]: "重要",
  [AnnouncementPriority.URGENT]: "紧急",
};
