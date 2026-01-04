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

/**
 * 获取工单列表
 */
export async function getTickets(
  params?: PageParams & TicketQueryParams,
): Promise<Page<TicketVO>> {
  return api.get<Page<TicketVO>>(BASE, { params });
}

/**
 * 获取工单详情
 * @see TicketController.getDetail
 * @note 后端返回 TicketDetailVO，包含 attachments 和 replies
 */
export async function getTicket(id: string): Promise<TicketVO> {
  return api.get<TicketVO>(`${BASE}/${id}`);
}

/**
 * 创建工单
 * @see TicketController.create
 */
export async function createTicket(
  data: CreateTicketRequest,
): Promise<TicketVO> {
  return api.post<TicketVO>(BASE, data);
}

/**
 * 更新工单
 * @see TicketController.update
 * @see TicketUpdateVO.java
 */
export async function updateTicket(
  id: string,
  data: UpdateTicketRequest,
): Promise<TicketVO> {
  return api.put<TicketVO>(`${BASE}/${id}`, data);
}

/**
 * 关闭工单
 * @note 后端使用 POST 方法
 */
export async function closeTicket(id: string): Promise<void> {
  return api.post(`${BASE}/${id}/close`);
}

/**
 * 获取工单回复列表
 * @deprecated 后端未提供独立的回复列表接口，回复包含在 getTicket 返回的 TicketDetailVO.replies 中
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
 * 回复工单
 * @see TicketController.reply
 * @note 后端返回 Result<String>，不是 TicketReplyVO
 */
export async function replyTicket(data: TicketReplyRequest): Promise<void> {
  await api.post(`${BASE}/${data.ticketId}/reply`, { content: data.content });
}

/**
 * 确认工单完成 (用户确认问题已解决)
 * @note 后端使用 POST 方法
 */
export async function confirmTicket(id: string): Promise<void> {
  return api.post(`${BASE}/${id}/confirm`);
}

/**
 * 获取待处理工单数量
 */
export async function getPendingCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${BASE}/pending-count`);
}

// ==================== 管理员接口 ====================

/**
 * 获取所有工单列表（管理员）
 * @see TicketController.getAdminList
 * @requires ticket:admin permission
 */
export async function getAdminTickets(
  params?: PageParams & TicketQueryParams,
): Promise<Page<TicketVO>> {
  return api.get<Page<TicketVO>>(`${BASE}/admin/list`, { params });
}

/**
 * 分配工单处理人（管理员）
 * @see TicketController.assign
 * @requires ticket:admin permission
 */
export async function assignTicket(
  ticketId: string,
  assigneeId: string,
): Promise<void> {
  return api.put(`${BASE}/admin/${ticketId}/assign`, null, {
    params: { assigneeId },
  });
}

/**
 * 更新工单状态（管理员）
 * @see TicketController.updateStatus
 * @requires ticket:admin permission
 */
export async function updateTicketStatus(
  ticketId: string,
  status: number,
): Promise<void> {
  return api.put(`${BASE}/admin/${ticketId}/status`, null, {
    params: { status },
  });
}

/**
 * 获取管理员待处理工单数量
 * @see TicketController.getAdminPendingCount
 * @requires ticket:admin permission
 */
export async function getAdminPendingCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${BASE}/admin/pending-count`);
}
