import { api } from "../client";
import type {
  Page,
  PageParams,
  FriendVO,
  FriendRequestDetailVO,
  FriendFileShareDetailVO,
  UserSearchVO,
  SendFriendRequestParams,
  FriendShareParams,
  UpdateRemarkParams,
} from "../types";

// ===== Friend Requests =====

const FRIENDS_BASE = "/friends";

/**
 * 发送好友请求
 */
export async function sendFriendRequest(
  data: SendFriendRequestParams,
): Promise<FriendRequestDetailVO> {
  return api.post<FriendRequestDetailVO>(`${FRIENDS_BASE}/requests`, data);
}

/**
 * 获取收到的好友请求列表
 */
export async function getReceivedRequests(
  params?: PageParams,
): Promise<Page<FriendRequestDetailVO>> {
  return api.get<Page<FriendRequestDetailVO>>(
    `${FRIENDS_BASE}/requests/received`,
    { params },
  );
}

/**
 * 获取发送的好友请求列表
 */
export async function getSentRequests(
  params?: PageParams,
): Promise<Page<FriendRequestDetailVO>> {
  return api.get<Page<FriendRequestDetailVO>>(`${FRIENDS_BASE}/requests/sent`, {
    params,
  });
}

/**
 * 接受好友请求
 */
export async function acceptFriendRequest(requestId: string): Promise<void> {
  return api.post(`${FRIENDS_BASE}/requests/${requestId}/accept`);
}

/**
 * 拒绝好友请求
 */
export async function rejectFriendRequest(requestId: string): Promise<void> {
  return api.post(`${FRIENDS_BASE}/requests/${requestId}/reject`);
}

/**
 * 取消好友请求
 */
export async function cancelFriendRequest(requestId: string): Promise<void> {
  return api.delete(`${FRIENDS_BASE}/requests/${requestId}`);
}

/**
 * 获取待处理的好友请求数量
 */
export async function getPendingRequestCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${FRIENDS_BASE}/requests/pending-count`);
}

// ===== Friends List =====

/**
 * 获取好友列表（分页）
 */
export async function getFriends(params?: PageParams): Promise<Page<FriendVO>> {
  return api.get<Page<FriendVO>>(FRIENDS_BASE, { params });
}

/**
 * 获取所有好友（用于选择器）
 */
export async function getAllFriends(): Promise<FriendVO[]> {
  return api.get<FriendVO[]>(`${FRIENDS_BASE}/all`);
}

/**
 * 解除好友关系
 */
export async function unfriend(friendId: string): Promise<void> {
  return api.delete(`${FRIENDS_BASE}/${friendId}`);
}

/**
 * 更新好友备注
 */
export async function updateFriendRemark(
  friendId: string,
  data: UpdateRemarkParams,
): Promise<void> {
  return api.put(`${FRIENDS_BASE}/${friendId}/remark`, data);
}

/**
 * 搜索用户
 */
export async function searchUsers(keyword: string): Promise<UserSearchVO[]> {
  return api.get<UserSearchVO[]>(`${FRIENDS_BASE}/search`, {
    params: { keyword },
  });
}

// ===== Friend File Shares =====

const FRIEND_SHARES_BASE = "/friend-shares";

/**
 * 分享文件给好友
 */
export async function shareToFriend(
  data: FriendShareParams,
): Promise<FriendFileShareDetailVO> {
  return api.post<FriendFileShareDetailVO>(FRIEND_SHARES_BASE, data);
}

/**
 * 获取收到的好友分享列表
 */
export async function getReceivedFriendShares(
  params?: PageParams,
): Promise<Page<FriendFileShareDetailVO>> {
  return api.get<Page<FriendFileShareDetailVO>>(
    `${FRIEND_SHARES_BASE}/received`,
    { params },
  );
}

/**
 * 获取发送的好友分享列表
 */
export async function getSentFriendShares(
  params?: PageParams,
): Promise<Page<FriendFileShareDetailVO>> {
  return api.get<Page<FriendFileShareDetailVO>>(`${FRIEND_SHARES_BASE}/sent`, {
    params,
  });
}

/**
 * 获取好友分享详情
 */
export async function getFriendShareDetail(
  shareId: string,
): Promise<FriendFileShareDetailVO> {
  return api.get<FriendFileShareDetailVO>(`${FRIEND_SHARES_BASE}/${shareId}`);
}

/**
 * 标记好友分享为已读
 */
export async function markFriendShareAsRead(shareId: string): Promise<void> {
  return api.post(`${FRIEND_SHARES_BASE}/${shareId}/read`);
}

/**
 * 取消好友分享
 */
export async function cancelFriendShare(shareId: string): Promise<void> {
  return api.delete(`${FRIEND_SHARES_BASE}/${shareId}`);
}

/**
 * 获取未读好友分享数量
 */
export async function getUnreadFriendShareCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${FRIEND_SHARES_BASE}/unread-count`);
}
