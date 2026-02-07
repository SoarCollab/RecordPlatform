import { api } from "../client";
import type {
  Page,
  PageParams,
  ConversationVO,
  ConversationDetailVO,
  MessageVO,
  SendMessageRequest,
  AnnouncementVO,
} from "../types";

const CONV_BASE = "/conversations";
const MSG_BASE = "/messages";
const ANN_BASE = "/announcements";

/**
 * 获取会话列表。
 *
 * @param params 分页参数
 * @returns 会话分页
 */
export async function getConversations(
  params?: PageParams,
): Promise<Page<ConversationVO>> {
  return api.get<Page<ConversationVO>>(CONV_BASE, { params });
}

/**
 * 获取会话详情。
 *
 * @param id 会话 ID
 * @param params 分页参数
 * @returns 会话详情
 */
export async function getConversationDetail(
  id: string,
  params?: { pageNum?: number; pageSize?: number },
): Promise<ConversationDetailVO> {
  return api.get<ConversationDetailVO>(`${CONV_BASE}/${id}`, { params });
}

/**
 * 获取会话详情（别名）。
 *
 * @param id 会话 ID
 * @param params 分页参数
 * @returns 会话详情
 */
export async function getConversation(
  id: string,
  params?: { pageNum?: number; pageSize?: number },
): Promise<ConversationDetailVO> {
  return getConversationDetail(id, params);
}

/**
 * 删除会话。
 *
 * @param id 会话 ID
 */
export async function deleteConversation(id: string): Promise<void> {
  await api.delete(`${CONV_BASE}/${id}`);
}

/**
 * 标记会话已读。
 *
 * @param conversationId 会话 ID
 */
export async function markAsRead(conversationId: string): Promise<void> {
  await api.put(`${CONV_BASE}/${conversationId}/read-status`);
}

/**
 * 发送私信。
 *
 * @param data 消息参数
 * @returns 消息视图
 */
export async function sendMessage(
  data: SendMessageRequest,
): Promise<MessageVO> {
  return api.post<MessageVO>(MSG_BASE, {
    receiverId: data.receiverId,
    content: data.content,
    contentType: data.contentType ?? "text",
  });
}

/**
 * 获取未读私信数量。
 *
 * @returns 未读数量
 */
export async function getUnreadMessageCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${MSG_BASE}/unread-count`);
}

/**
 * 获取未读会话数量。
 *
 * @returns 未读数量
 */
export async function getUnreadConversationCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${CONV_BASE}/unread-count`);
}

/**
 * 获取公告列表。
 *
 * @param params 分页参数
 * @returns 公告分页
 */
export async function getAnnouncements(
  params?: PageParams,
): Promise<Page<AnnouncementVO>> {
  return api.get<Page<AnnouncementVO>>(ANN_BASE, { params });
}

/**
 * 获取公告详情。
 *
 * @param id 公告 ID
 * @returns 公告详情
 */
export async function getAnnouncement(id: string): Promise<AnnouncementVO> {
  return api.get<AnnouncementVO>(`${ANN_BASE}/${id}`);
}

/**
 * 获取最新公告。
 *
 * @param limit 数量限制
 * @returns 公告列表
 */
export async function getLatestAnnouncements(
  limit = 5,
): Promise<AnnouncementVO[]> {
  return api.get<AnnouncementVO[]>(`${ANN_BASE}/latest`, { params: { limit } });
}

/**
 * 获取未读公告数量。
 *
 * @returns 未读数量
 */
export async function getUnreadAnnouncementCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${ANN_BASE}/unread-count`);
}

/**
 * 标记公告已读。
 *
 * @param id 公告 ID
 */
export async function markAnnouncementAsRead(id: string): Promise<void> {
  await api.put(`${ANN_BASE}/${id}/read-status`);
}

/**
 * 标记全部公告已读。
 */
export async function markAllAnnouncementsAsRead(): Promise<void> {
  await api.put(`${ANN_BASE}/read-status`);
}
