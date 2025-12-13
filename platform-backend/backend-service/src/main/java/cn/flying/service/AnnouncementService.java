package cn.flying.service;

import cn.flying.dao.entity.Announcement;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 公告服务接口
 */
public interface AnnouncementService extends IService<Announcement> {

    /**
     * 发布/保存公告
     *
     * @param publisherId 发布者ID
     * @param vo          公告创建参数
     * @return 公告实体
     */
    Announcement publish(Long publisherId, AnnouncementCreateVO vo);

    /**
     * 更新公告
     *
     * @param announcementId 公告ID
     * @param vo             公告更新参数
     * @return 更新后的公告
     */
    Announcement update(Long announcementId, AnnouncementCreateVO vo);

    /**
     * 获取已发布公告列表（用户端，分页）
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 公告分页数据
     */
    IPage<AnnouncementVO> getPublishedList(Long userId, Page<Announcement> page);

    /**
     * 获取所有公告列表（管理端，分页）
     *
     * @param page 分页参数
     * @return 公告分页数据
     */
    IPage<AnnouncementVO> getAdminList(Page<Announcement> page);

    /**
     * 获取公告详情
     *
     * @param userId         当前用户ID
     * @param announcementId 公告ID
     * @return 公告详情
     */
    AnnouncementVO getDetail(Long userId, Long announcementId);

    /**
     * 获取未读公告数量
     *
     * @param userId 用户ID
     * @return 未读数量
     */
    int getUnreadCount(Long userId);

    /**
     * 标记公告为已读
     *
     * @param userId         用户ID
     * @param announcementId 公告ID
     */
    void markAsRead(Long userId, Long announcementId);

    /**
     * 标记所有公告为已读
     *
     * @param userId 用户ID
     */
    void markAllAsRead(Long userId);

    /**
     * 删除公告
     *
     * @param announcementId 公告ID
     */
    void deleteAnnouncement(Long announcementId);

    /**
     * 处理定时发布的公告
     */
    void processScheduledAnnouncements();

    /**
     * 处理过期公告
     */
    void processExpiredAnnouncements();

    /**
     * 获取最新公告列表
     *
     * @param userId 当前用户ID
     * @param limit  数量限制
     * @return 最新公告列表
     */
    List<AnnouncementVO> getLatest(Long userId, int limit);
}
