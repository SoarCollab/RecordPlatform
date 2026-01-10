/**
 * 工单信息
 * @see TicketVO.java
 * @note content 和 category 仅在 TicketDetailVO 中返回
 */
export interface TicketVO {
  id: string;
  ticketNo: string;
  title: string;
  priority: TicketPriority;
  priorityDesc?: string;
  status: TicketStatus;
  statusDesc?: string;
  creatorId: string;
  creatorUsername: string;
  assigneeId?: string;
  assigneeUsername?: string;
  replyCount: number;
  createTime: string;
  updateTime?: string;
  closeTime?: string;
  // 以下字段仅在详情接口 (TicketDetailVO) 中返回
  content?: string;
  category?: TicketCategory;
}

/**
 * 工单类别
 */
export enum TicketCategory {
  BUG = 0,
  FEATURE = 1,
  QUESTION = 2,
  FEEDBACK = 3,
  OTHER = 99,
}

/**
 * 工单优先级
 * @see TicketPriority.java
 */
export enum TicketPriority {
  LOW = 0,
  MEDIUM = 1,
  HIGH = 2,
}

/**
 * 工单状态
 * @see TicketStatus.java
 */
export enum TicketStatus {
  PENDING = 0,
  PROCESSING = 1,
  CONFIRMING = 2,
  COMPLETED = 3,
  CLOSED = 4,
}

/**
 * 工单类别标签
 */
export const TicketCategoryLabel: Record<TicketCategory, string> = {
  [TicketCategory.BUG]: "Bug",
  [TicketCategory.FEATURE]: "功能请求",
  [TicketCategory.QUESTION]: "问题咨询",
  [TicketCategory.FEEDBACK]: "反馈建议",
  [TicketCategory.OTHER]: "其他",
};

/**
 * 工单优先级标签
 */
export const TicketPriorityLabel: Record<TicketPriority, string> = {
  [TicketPriority.LOW]: "低",
  [TicketPriority.MEDIUM]: "中",
  [TicketPriority.HIGH]: "高",
};

/**
 * 工单状态标签
 */
export const TicketStatusLabel: Record<TicketStatus, string> = {
  [TicketStatus.PENDING]: "待处理",
  [TicketStatus.PROCESSING]: "处理中",
  [TicketStatus.CONFIRMING]: "待确认",
  [TicketStatus.COMPLETED]: "已完成",
  [TicketStatus.CLOSED]: "已关闭",
};

/**
 * 工单回复
 * @see TicketReplyVO.java
 */
export interface TicketReplyVO {
  id: string;
  ticketId: string;
  content: string;
  replierId: string;
  replierName: string;
  replierAvatar?: string;
  isInternal: boolean;
  isAdmin: boolean;
  createTime: string;
}

/**
 * 创建工单请求
 * @see TicketCreateVO.java
 */
export interface CreateTicketRequest {
  title: string;
  content: string;
  category?: TicketCategory;
  priority?: TicketPriority;
}

/**
 * 更新工单请求
 * @see TicketUpdateVO.java
 */
export interface UpdateTicketRequest {
  title?: string;
  content?: string;
  priority?: TicketPriority;
  category?: TicketCategory;
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
