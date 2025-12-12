import { api } from '../client';
import type {
	Page,
	PageParams,
	TicketVO,
	TicketReplyVO,
	CreateTicketRequest,
	TicketReplyRequest,
	TicketQueryParams
} from '../types';

const BASE = '/tickets';

/**
 * 获取工单列表
 */
export async function getTickets(
	params?: PageParams & TicketQueryParams
): Promise<Page<TicketVO>> {
	return api.get<Page<TicketVO>>(BASE, { params });
}

/**
 * 获取工单详情
 */
export async function getTicket(id: string): Promise<TicketVO> {
	return api.get<TicketVO>(`${BASE}/${id}`);
}

/**
 * 创建工单
 */
export async function createTicket(data: CreateTicketRequest): Promise<TicketVO> {
	return api.post<TicketVO>(BASE, data);
}

/**
 * 更新工单
 */
export async function updateTicket(
	id: string,
	data: Partial<CreateTicketRequest>
): Promise<TicketVO> {
	return api.put<TicketVO>(`${BASE}/${id}`, data);
}

/**
 * 关闭工单
 */
export async function closeTicket(id: string): Promise<void> {
	return api.put(`${BASE}/${id}/close`);
}

/**
 * 获取工单回复列表
 */
export async function getTicketReplies(
	ticketId: string,
	params?: PageParams
): Promise<Page<TicketReplyVO>> {
	return api.get<Page<TicketReplyVO>>(`${BASE}/${ticketId}/replies`, { params });
}

/**
 * 回复工单
 */
export async function replyTicket(data: TicketReplyRequest): Promise<TicketReplyVO> {
	return api.post<TicketReplyVO>(`${BASE}/${data.ticketId}/reply`, data);
}

/**
 * 确认工单完成 (用户确认问题已解决)
 */
export async function confirmTicket(id: string): Promise<void> {
	return api.put(`${BASE}/${id}/confirm`);
}

/**
 * 获取待处理工单数量
 */
export async function getPendingCount(): Promise<{ count: number }> {
	return api.get<{ count: number }>(`${BASE}/pending-count`);
}
