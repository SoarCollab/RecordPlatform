package cn.flying.service;

import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.vo.friend.FriendRequestDetailVO;
import cn.flying.dao.vo.friend.FriendVO;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.dao.vo.friend.UserSearchVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 好友服务接口
 */
public interface FriendService extends IService<Friendship> {

    /**
     * 判断两个用户是否是好友
     *
     * @param userA 用户A
     * @param userB 用户B
     * @return 是否是好友
     */
    boolean areFriends(Long userA, Long userB);

    /**
     * 发送好友请求
     *
     * @param requesterId 请求者ID
     * @param vo          请求参数
     * @return 好友请求
     */
    FriendRequest sendRequest(Long requesterId, SendFriendRequestVO vo);

    /**
     * 接受好友请求
     *
     * @param userId    当前用户ID
     * @param requestId 请求ID(外部ID)
     */
    void acceptRequest(Long userId, String requestId);

    /**
     * 拒绝好友请求
     *
     * @param userId    当前用户ID
     * @param requestId 请求ID(外部ID)
     */
    void rejectRequest(Long userId, String requestId);

    /**
     * 取消好友请求
     *
     * @param userId    当前用户ID
     * @param requestId 请求ID(外部ID)
     */
    void cancelRequest(Long userId, String requestId);

    /**
     * 获取收到的好友请求列表
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 请求分页数据
     */
    IPage<FriendRequestDetailVO> getReceivedRequests(Long userId, Page<?> page);

    /**
     * 获取发送的好友请求列表
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 请求分页数据
     */
    IPage<FriendRequestDetailVO> getSentRequests(Long userId, Page<?> page);

    /**
     * 获取好友列表(分页)
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 好友分页数据
     */
    IPage<FriendVO> getFriends(Long userId, Page<?> page);

    /**
     * 获取所有好友(用于选择器)
     *
     * @param userId 当前用户ID
     * @return 好友列表
     */
    List<FriendVO> getAllFriends(Long userId);

    /**
     * 解除好友关系
     *
     * @param userId   当前用户ID
     * @param friendId 好友ID(外部ID)
     */
    void unfriend(Long userId, String friendId);

    /**
     * 更新好友备注
     *
     * @param userId   当前用户ID
     * @param friendId 好友ID(外部ID)
     * @param remark   备注
     */
    void updateRemark(Long userId, String friendId, String remark);

    /**
     * 获取待处理的好友请求数量
     *
     * @param userId 用户ID
     * @return 待处理数量
     */
    int getPendingCount(Long userId);

    /**
     * 搜索用户
     *
     * @param userId  当前用户ID
     * @param keyword 关键词
     * @return 用户列表
     */
    List<UserSearchVO> searchUsers(Long userId, String keyword);
}
