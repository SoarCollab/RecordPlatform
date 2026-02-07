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

const FRIENDS_BASE = "/friends";
const FRIEND_SHARES_BASE = "/friend-shares";

/**
 * 发送好友请求。
 *
 * @param data 请求参数
 * @returns 好友请求详情
 */
export async function sendFriendRequest(
  data: SendFriendRequestParams,
): Promise<FriendRequestDetailVO> {
  return api.post<FriendRequestDetailVO>(`${FRIENDS_BASE}/requests`, data);
}

/**
 * 获取收到的好友请求列表。
 *
 * @param params 分页参数
 * @returns 分页结果
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
 * 获取发送的好友请求列表。
 *
 * @param params 分页参数
 * @returns 分页结果
 */
export async function getSentRequests(
  params?: PageParams,
): Promise<Page<FriendRequestDetailVO>> {
  return api.get<Page<FriendRequestDetailVO>>(`${FRIENDS_BASE}/requests/sent`, {
    params,
  });
}

/**
 * 接受好友请求。
 *
 * @param requestId 请求 ID
 */
export async function acceptFriendRequest(requestId: string): Promise<void> {
  await api.put(`${FRIENDS_BASE}/requests/${requestId}/status`, null, {
    params: { status: "accept" },
  });
}

/**
 * 拒绝好友请求。
 *
 * @param requestId 请求 ID
 */
export async function rejectFriendRequest(requestId: string): Promise<void> {
  await api.put(`${FRIENDS_BASE}/requests/${requestId}/status`, null, {
    params: { status: "reject" },
  });
}

/**
 * 取消好友请求。
 *
 * @param requestId 请求 ID
 */
export async function cancelFriendRequest(requestId: string): Promise<void> {
  await api.delete(`${FRIENDS_BASE}/requests/${requestId}`);
}

/**
 * 获取待处理好友请求数量。
 *
 * @returns 待处理数量
 */
export async function getPendingRequestCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${FRIENDS_BASE}/requests/pending-count`);
}

/**
 * 获取好友列表（分页）。
 *
 * @param params 分页参数
 * @returns 分页结果
 */
export async function getFriends(params?: PageParams): Promise<Page<FriendVO>> {
  return api.get<Page<FriendVO>>(FRIENDS_BASE, { params });
}

/**
 * 获取所有好友（用于选择器）。
 *
 * @returns 好友列表
 */
export async function getAllFriends(): Promise<FriendVO[]> {
  return api.get<FriendVO[]>(`${FRIENDS_BASE}/all`);
}

/**
 * 解除好友关系。
 *
 * @param friendId 好友 ID
 */
export async function unfriend(friendId: string): Promise<void> {
  await api.delete(`${FRIENDS_BASE}/${friendId}`);
}

/**
 * 更新好友备注。
 *
 * @param friendId 好友 ID
 * @param data 备注内容
 */
export async function updateFriendRemark(
  friendId: string,
  data: UpdateRemarkParams,
): Promise<void> {
  await api.put(`${FRIENDS_BASE}/${friendId}/remark`, data);
}

/**
 * 搜索用户。
 *
 * @param keyword 关键词
 * @returns 用户列表
 */
export async function searchUsers(keyword: string): Promise<UserSearchVO[]> {
  return api.get<UserSearchVO[]>(`${FRIENDS_BASE}/search`, {
    params: { keyword },
  });
}

/**
 * 分享文件给好友。
 *
 * @param data 分享参数
 * @returns 分享详情
 */
export async function shareToFriend(
  data: FriendShareParams,
): Promise<FriendFileShareDetailVO> {
  return api.post<FriendFileShareDetailVO>(FRIEND_SHARES_BASE, data);
}

/**
 * 获取收到的好友分享列表。
 *
 * @param params 分页参数
 * @returns 分页结果
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
 * 获取发送的好友分享列表。
 *
 * @param params 分页参数
 * @returns 分页结果
 */
export async function getSentFriendShares(
  params?: PageParams,
): Promise<Page<FriendFileShareDetailVO>> {
  return api.get<Page<FriendFileShareDetailVO>>(`${FRIEND_SHARES_BASE}/sent`, {
    params,
  });
}

/**
 * 获取好友分享详情。
 *
 * @param shareId 分享 ID
 * @returns 分享详情
 */
export async function getFriendShareDetail(
  shareId: string,
): Promise<FriendFileShareDetailVO> {
  return api.get<FriendFileShareDetailVO>(`${FRIEND_SHARES_BASE}/${shareId}`);
}

/**
 * 标记好友分享为已读。
 *
 * @param shareId 分享 ID
 */
export async function markFriendShareAsRead(shareId: string): Promise<void> {
  await api.put(`${FRIEND_SHARES_BASE}/${shareId}/read-status`);
}

/**
 * 取消好友分享。
 *
 * @param shareId 分享 ID
 */
export async function cancelFriendShare(shareId: string): Promise<void> {
  await api.delete(`${FRIEND_SHARES_BASE}/${shareId}`);
}

/**
 * 获取未读好友分享数量。
 *
 * @returns 未读数量
 */
export async function getUnreadFriendShareCount(): Promise<{ count: number }> {
  return api.get<{ count: number }>(`${FRIEND_SHARES_BASE}/unread-count`);
}
