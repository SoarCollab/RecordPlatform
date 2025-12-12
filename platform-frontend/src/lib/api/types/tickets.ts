/**
 * 工单信息
 * @see TicketVO.java
 */
export interface TicketVO {
	id: string;
	ticketNo: string;
	title: string;
	content: string;
	category: TicketCategory;
	priority: TicketPriority;
	status: TicketStatus;
	replyCount: number;
	creatorId: string;
	creatorUsername: string;
	assigneeId?: string;
	assigneeUsername?: string;
	createTime: string;
	updateTime?: string;
	closeTime?: string;
}

/**
 * 工单类别
 */
export enum TicketCategory {
	BUG = 0,
	FEATURE = 1,
	QUESTION = 2,
	FEEDBACK = 3,
	OTHER = 99
}

/**
 * 工单优先级
 */
export enum TicketPriority {
	LOW = 0,
	NORMAL = 1,
	HIGH = 2,
	URGENT = 3
}

/**
 * 工单状态
 */
export enum TicketStatus {
	OPEN = 0,
	IN_PROGRESS = 1,
	PENDING = 2,
	RESOLVED = 3,
	CLOSED = 4
}

/**
 * 工单类别标签
 */
export const TicketCategoryLabel: Record<TicketCategory, string> = {
	[TicketCategory.BUG]: 'Bug',
	[TicketCategory.FEATURE]: '功能请求',
	[TicketCategory.QUESTION]: '问题咨询',
	[TicketCategory.FEEDBACK]: '反馈建议',
	[TicketCategory.OTHER]: '其他'
};

/**
 * 工单优先级标签
 */
export const TicketPriorityLabel: Record<TicketPriority, string> = {
	[TicketPriority.LOW]: '低',
	[TicketPriority.NORMAL]: '普通',
	[TicketPriority.HIGH]: '高',
	[TicketPriority.URGENT]: '紧急'
};

/**
 * 工单状态标签
 */
export const TicketStatusLabel: Record<TicketStatus, string> = {
	[TicketStatus.OPEN]: '待处理',
	[TicketStatus.IN_PROGRESS]: '处理中',
	[TicketStatus.PENDING]: '等待反馈',
	[TicketStatus.RESOLVED]: '已解决',
	[TicketStatus.CLOSED]: '已关闭'
};

/**
 * 工单回复
 * @see TicketReplyVO.java
 */
export interface TicketReplyVO {
	id: string;
	ticketId: string;
	content: string;
	replyerId: string;
	replyerUsername: string;
	replyerNickname?: string;
	isStaff: boolean;
	createTime: string;
}

/**
 * 创建工单请求
 */
export interface CreateTicketRequest {
	title: string;
	content: string;
	category: TicketCategory;
	priority?: TicketPriority;
}

/**
 * 工单回复请求
 */
export interface TicketReplyRequest {
	ticketId: string;
	content: string;
}

/**
 * 工单查询参数
 */
export interface TicketQueryParams {
	keyword?: string;
	category?: TicketCategory;
	priority?: TicketPriority;
	status?: TicketStatus;
}
