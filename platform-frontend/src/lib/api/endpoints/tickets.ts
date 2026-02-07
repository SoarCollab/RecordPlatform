import { api } from "../client";
import type {
  Page,
  PageParams,
  TicketVO,
  TicketReplyVO,
  CreateTicketRequest,
  UpdateTicketRequest,
  TicketReplyRequest,
  TicketQueryParams,
} from "../types";

const BASE = "/tickets";
const ADMIN_BASE = "/admin/tickets";

/**
 * 获取工单列表。
 *
 * @param params 查询参数
 * @returns 工单分页
 */
export async function getTickets(
  params?: PageParams & TicketQueryParams,
): Promise<Page<TicketVO>> {
  return api.get<Page<TicketVO>>(BASE, { params });
}

/**
 * 获取工单详情。
 *
 * @param id 工单 ID
 * @returns 工单详情
 */
export async function getTicket(id: string): Promise<TicketVO> {
  return api.get<TicketVO>(`${BASE}/${id}`);
}

/**
 * 创建工单。
 *
 * @param data 创建参数
 * @returns 工单详情
 */
export async function createTicket(
  data: CreateTicketRequest,
): Promise<TicketVO> {
  return api.post<TicketVO>(BASE, data);
}

/**
 * 更新工单。
 *
 * @param id 工单 ID
 * @param data 更新参数
 * @returns 工单详情
 */
export async function updateTicket(
  id: string,
  data: UpdateTicketRequest,
): Promise<TicketVO> {
  return api.put<TicketVO>(`${BASE}/${id}`, data);
}

/**
 * 关闭工单。
 *
 * @param id 工单 ID
 */
export async function closeTicket(id: string): Promise<void> {
  await api.post(`${BASE}/${id}/close`);
}

/**
 * 获取工单回复列表。
 *
 * @deprecated 后端未提供独立接口，请使用 getTicket 返回结果中的 replies
 */
export async function getTicketReplies(
  _ticketId: string,
  _params?: PageParams,
): Promise<Page<TicketReplyVO>> {
  throw new Error(
    "后端未提供独立的回复列表接口，请从 getTicket 返回的 replies 字段获取",
  );
}

/**
 * 回复工单。
 *
 * @param data 回复参数
 */
export async function replyTicket(data: TicketReplyRequest): Promise<void> {
  await api.post(`${BASE}/${data.ticketId}/reply`, { content: data.content });
}

/**
 * 确认工单完成。
 *
 * @param id 工单 ID
 */
export async function confirmTicket(id: string): Promise<void> {
  await api.post(`${BASE}/${id}/confirm`);
}

/**
 * 获取待处理工单数量。
 *
 * @returns 待处理数量
 */
export async function getPendingCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${BASE}/pending-count`);
}

/**
 * 获取未读工单数量。
 *
 * @returns 未读数量
 */
export async function getUnreadCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${BASE}/unread-count`);
}

/**
 * 获取管理员工单列表。
 *
 * @param params 查询参数
 * @returns 工单分页
 */
export async function getAdminTickets(
  params?: PageParams & TicketQueryParams,
): Promise<Page<TicketVO>> {
  return api.get<Page<TicketVO>>(ADMIN_BASE, { params });
}

/**
 * 分配工单处理人。
 *
 * @param ticketId 工单 ID
 * @param assigneeId 处理人 ID
 */
export async function assignTicket(
  ticketId: string,
  assigneeId: string,
): Promise<void> {
  await api.put(`${ADMIN_BASE}/${ticketId}/assignee`, null, { params: { assigneeId } });
}

/**
 * 更新工单状态。
 *
 * @param ticketId 工单 ID
 * @param status 新状态
 */
export async function updateTicketStatus(
  ticketId: string,
  status: number,
): Promise<void> {
  await api.put(`${ADMIN_BASE}/${ticketId}/status`, null, { params: { status } });
}

/**
 * 获取管理员待处理工单数量。
 *
 * @returns 待处理数量
 */
export async function getAdminPendingCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${ADMIN_BASE}/pending-count`);
}
