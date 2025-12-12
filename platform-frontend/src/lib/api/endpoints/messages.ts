import { api } from '../client';
import type {
	Page,
	PageParams,
	ConversationVO,
	MessageVO,
	SendMessageRequest,
	AnnouncementVO
} from '../types';

// ===== Conversations =====

const CONV_BASE = '/conversations';

/**
 * 获取会话列表
 */
export async function getConversations(params?: PageParams): Promise<Page<ConversationVO>> {
	return api.get<Page<ConversationVO>>(CONV_BASE, { params });
}

/**
 * 获取会话详情
 */
export async function getConversation(id: string): Promise<ConversationVO> {
	return api.get<ConversationVO>(`${CONV_BASE}/${id}`);
}

/**
 * 获取或创建与指定用户的会话
 */
export async function getOrCreateConversation(username: string): Promise<ConversationVO> {
	return api.post<ConversationVO>(`${CONV_BASE}/with/${username}`);
}

/**
 * 删除会话
 */
export async function deleteConversation(id: string): Promise<void> {
	return api.delete(`${CONV_BASE}/${id}`);
}

// ===== Messages =====

const MSG_BASE = '/messages';

/**
 * 获取会话消息列表
 */
export async function getMessages(
	conversationId: string,
	params?: PageParams
): Promise<Page<MessageVO>> {
	return api.get<Page<MessageVO>>(`${CONV_BASE}/${conversationId}/messages`, { params });
}

/**
 * 发送消息
 */
export async function sendMessage(data: SendMessageRequest): Promise<MessageVO> {
	return api.post<MessageVO>(MSG_BASE, data);
}

/**
 * 标记会话已读
 */
export async function markAsRead(conversationId: string): Promise<void> {
	return api.post(`${CONV_BASE}/${conversationId}/read`);
}

/**
 * 获取未读消息数量
 */
export async function getUnreadMessageCount(): Promise<number> {
	return api.get<number>(`${MSG_BASE}/unread-count`);
}

/**
 * 获取未读会话数量
 */
export async function getUnreadConversationCount(): Promise<{ count: number }> {
	return api.get<{ count: number }>(`${CONV_BASE}/unread-count`);
}

// ===== Announcements =====

const ANN_BASE = '/announcements';

/**
 * 获取公告列表
 */
export async function getAnnouncements(params?: PageParams): Promise<Page<AnnouncementVO>> {
	return api.get<Page<AnnouncementVO>>(ANN_BASE, { params });
}

/**
 * 获取公告详情
 */
export async function getAnnouncement(id: string): Promise<AnnouncementVO> {
	return api.get<AnnouncementVO>(`${ANN_BASE}/${id}`);
}

/**
 * 获取最新公告
 */
export async function getLatestAnnouncements(limit = 5): Promise<AnnouncementVO[]> {
	return api.get<AnnouncementVO[]>(`${ANN_BASE}/latest`, { params: { limit } });
}

/**
 * 获取公告未读数量
 */
export async function getUnreadAnnouncementCount(): Promise<{ count: number }> {
	return api.get<{ count: number }>(`${ANN_BASE}/unread-count`);
}

/**
 * 标记公告已读
 */
export async function markAnnouncementAsRead(id: string): Promise<void> {
	return api.post(`${ANN_BASE}/${id}/read`);
}
