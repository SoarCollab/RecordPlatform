package cn.flying.service;

import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.vo.friend.FriendFileShareDetailVO;
import cn.flying.dao.vo.friend.FriendShareVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 好友文件分享服务接口
 */
public interface FriendFileShareService extends IService<FriendFileShare> {

    /**
     * 分享文件给好友
     *
     * @param sharerId 分享者ID
     * @param vo       分享参数
     * @return 分享记录
     */
    FriendFileShare shareToFriend(Long sharerId, FriendShareVO vo);

    /**
     * 获取收到的好友分享列表
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 分享分页数据
     */
    IPage<FriendFileShareDetailVO> getReceivedShares(Long userId, Page<?> page);

    /**
     * 获取发送的好友分享列表
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 分享分页数据
     */
    IPage<FriendFileShareDetailVO> getSentShares(Long userId, Page<?> page);

    /**
     * 标记分享为已读
     *
     * @param userId  当前用户ID
     * @param shareId 分享ID(外部ID)
     */
    void markAsRead(Long userId, String shareId);

    /**
     * 取消分享
     *
     * @param userId  当前用户ID
     * @param shareId 分享ID(外部ID)
     */
    void cancelShare(Long userId, String shareId);

    /**
     * 获取未读分享数量
     *
     * @param userId 用户ID
     * @return 未读数量
     */
    int getUnreadCount(Long userId);

    /**
     * 获取好友分享详情
     *
     * @param userId  当前用户ID
     * @param shareId 分享ID(外部ID)
     * @return 分享详情
     */
    FriendFileShareDetailVO getShareDetail(Long userId, String shareId);

    /**
     * 检查用户是否通过好友分享有权访问指定文件
     *
     * @param userId   当前用户ID
     * @param fileHash 文件哈希
     * @return 如果有权访问则返回分享者ID，否则返回null
     */
    Long getSharerIdForFile(Long userId, String fileHash);
}
