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

export async function getConversations(
  params?: PageParams,
): Promise<Page<ConversationVO>> {
  return api.get<Page<ConversationVO>>(CONV_BASE, { params });
}

export async function getConversationDetail(
  id: string,
  params?: { pageNum?: number; pageSize?: number },
): Promise<ConversationDetailVO> {
  return api.get<ConversationDetailVO>(`${CONV_BASE}/${id}`, { params });
}

export async function getConversation(
  id: string,
  params?: { pageNum?: number; pageSize?: number },
): Promise<ConversationDetailVO> {
  return getConversationDetail(id, params);
}

export async function deleteConversation(id: string): Promise<void> {
  await api.delete(`${CONV_BASE}/${id}`);
}

export async function markAsRead(conversationId: string): Promise<void> {
  await api.post(`${CONV_BASE}/${conversationId}/read`);
}

export async function sendMessage(
  data: SendMessageRequest,
): Promise<MessageVO> {
  return api.post<MessageVO>(MSG_BASE, {
    receiverId: data.receiverId,
    content: data.content,
    contentType: data.contentType ?? "text",
  });
}

export async function getUnreadMessageCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${MSG_BASE}/unread-count`);
}

export async function getUnreadConversationCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${CONV_BASE}/unread-count`);
}

export async function getAnnouncements(
  params?: PageParams,
): Promise<Page<AnnouncementVO>> {
  return api.get<Page<AnnouncementVO>>(ANN_BASE, { params });
}

export async function getAnnouncement(id: string): Promise<AnnouncementVO> {
  return api.get<AnnouncementVO>(`${ANN_BASE}/${id}`);
}

export async function getLatestAnnouncements(
  limit = 5,
): Promise<AnnouncementVO[]> {
  return api.get<AnnouncementVO[]>(`${ANN_BASE}/latest`, { params: { limit } });
}

export async function getUnreadAnnouncementCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${ANN_BASE}/unread-count`);
}

export async function markAnnouncementAsRead(id: string): Promise<void> {
  await api.post(`${ANN_BASE}/${id}/read`);
}

export async function markAllAnnouncementsAsRead(): Promise<void> {
  await api.post(`${ANN_BASE}/read-all`);
}
