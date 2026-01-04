/**
 * 好友系统类型定义
 */

/** 好友请求状态 */
export enum FriendRequestStatus {
  PENDING = 0,
  ACCEPTED = 1,
  REJECTED = 2,
  CANCELLED = 3,
}

/** 发送好友请求参数 */
export interface SendFriendRequestParams {
  addresseeId: string;
  message?: string;
}

/** 好友请求详情 */
export interface FriendRequestDetailVO {
  id: string;
  requesterId: string;
  requesterUsername: string;
  requesterAvatar?: string;
  addresseeId: string;
  addresseeUsername?: string;
  addresseeAvatar?: string;
  message?: string;
  status: FriendRequestStatus;
  createTime: string;
  updateTime?: string;
}

/** 好友信息 */
export interface FriendVO {
  id: string;
  friendshipId: string;
  username: string;
  avatar?: string;
  nickname?: string;
  remark?: string;
  friendSince: string;
}

/** 分享文件给好友参数 */
export interface FriendShareParams {
  friendId: string;
  fileHashes: string[];
  message?: string;
}

/** 好友文件分享详情 */
export interface FriendFileShareDetailVO {
  id: string;
  sharerId: string;
  sharerUsername: string;
  sharerAvatar?: string;
  friendId: string;
  friendUsername?: string;
  fileHashes: string[];
  fileNames: string[];
  fileCount: number;
  message?: string;
  isRead: boolean;
  createTime: string;
  readTime?: string;
}

/** 用户搜索结果 */
export interface UserSearchVO {
  id: string;
  username: string;
  avatar?: string;
  nickname?: string;
  isFriend: boolean;
  hasPendingRequest: boolean;
}

/** 更新备注参数 */
export interface UpdateRemarkParams {
  remark?: string;
}
