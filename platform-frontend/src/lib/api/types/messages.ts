/**
 * 会话信息
 * @see ConversationVO.java
 */
export interface ConversationVO {
	id: string;
	otherUserId: string;
	otherUsername: string;
	otherNickname?: string;
	otherAvatar?: string;
	lastMessageContent?: string;
	lastMessageTime?: string;
	unreadCount: number;
	createTime: string;
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
	SYSTEM = 99
}

/**
 * 消息状态
 */
export enum MessageStatus {
	SENDING = 0,
	SENT = 1,
	DELIVERED = 2,
	READ = 3,
	FAILED = -1
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
	status: AnnouncementStatus;
	publishTime?: string;
	createTime: string;
	author?: string;
}

/**
 * 公告优先级
 */
export enum AnnouncementPriority {
	LOW = 0,
	NORMAL = 1,
	HIGH = 2,
	URGENT = 3
}

/**
 * 公告状态
 */
export enum AnnouncementStatus {
	DRAFT = 0,
	PUBLISHED = 1,
	ARCHIVED = 2
}

/**
 * 公告优先级标签
 */
export const AnnouncementPriorityLabel: Record<AnnouncementPriority, string> = {
	[AnnouncementPriority.LOW]: '低',
	[AnnouncementPriority.NORMAL]: '普通',
	[AnnouncementPriority.HIGH]: '高',
	[AnnouncementPriority.URGENT]: '紧急'
};
