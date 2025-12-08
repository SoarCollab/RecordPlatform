package cn.flying.service.impl;

import cn.flying.common.constant.AnnouncementStatus;
import cn.flying.common.constant.MessagePriority;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.entity.AnnouncementRead;
import cn.flying.dao.mapper.AnnouncementMapper;
import cn.flying.dao.mapper.AnnouncementReadMapper;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.service.AccountService;
import cn.flying.service.AnnouncementService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公告服务实现
 */
@Slf4j
@Service
public class AnnouncementServiceImpl extends ServiceImpl<AnnouncementMapper, Announcement>
        implements AnnouncementService {

    @Resource
    private AnnouncementReadMapper announcementReadMapper;

    @Resource
    private AccountService accountService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Override
    @Transactional
    public Announcement publish(Long publisherId, AnnouncementCreateVO vo) {
        Announcement announcement = new Announcement()
                .setTitle(vo.getTitle())
                .setContent(vo.getContent())
                .setPriority(vo.getPriority())
                .setIsPinned(vo.getIsPinned())
                .setPublishTime(vo.getPublishTime())
                .setExpireTime(vo.getExpireTime())
                .setPublisherId(publisherId);

        // 根据发布时间判断状态
        if (vo.getStatus() == 0) {
            // 草稿
            announcement.setStatus(AnnouncementStatus.DRAFT.getCode());
        } else if (vo.getPublishTime() == null || vo.getPublishTime().before(new Date())) {
            // 立即发布
            announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
            announcement.setPublishTime(new Date());
        } else {
            // 定时发布（先保存为草稿）
            announcement.setStatus(AnnouncementStatus.DRAFT.getCode());
        }

        this.save(announcement);
        log.info("公告发布成功: id={}, title={}, status={}", announcement.getId(), announcement.getTitle(), announcement.getStatus());

        // 如果是立即发布，广播给所有在线用户
        if (announcement.getStatus() == AnnouncementStatus.PUBLISHED.getCode()) {
            broadcastNewAnnouncement(announcement);
        }

        return announcement;
    }

    @Override
    @Transactional
    public Announcement update(Long announcementId, AnnouncementCreateVO vo) {
        Announcement announcement = this.getById(announcementId);
        if (announcement == null) {
            throw new GeneralException(ResultEnum.ANNOUNCEMENT_NOT_FOUND);
        }

        announcement.setTitle(vo.getTitle())
                .setContent(vo.getContent())
                .setPriority(vo.getPriority())
                .setIsPinned(vo.getIsPinned())
                .setPublishTime(vo.getPublishTime())
                .setExpireTime(vo.getExpireTime());

        // 更新状态
        if (vo.getStatus() == 0) {
            announcement.setStatus(AnnouncementStatus.DRAFT.getCode());
        } else if (vo.getPublishTime() == null || vo.getPublishTime().before(new Date())) {
            announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
            if (announcement.getPublishTime() == null) {
                announcement.setPublishTime(new Date());
            }
        }

        this.updateById(announcement);
        log.info("公告更新成功: id={}", announcementId);
        return announcement;
    }

    @Override
    public IPage<AnnouncementVO> getPublishedList(Long userId, Page<Announcement> page) {
        // 查询已发布的公告
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, AnnouncementStatus.PUBLISHED.getCode())
                .orderByDesc(Announcement::getIsPinned)
                .orderByDesc(Announcement::getPublishTime);

        IPage<Announcement> announcementPage = this.page(page, wrapper);

        // 获取用户已读公告ID集合
        Set<Long> readIds = announcementReadMapper.selectReadAnnouncementIds(userId);

        // 转换为 VO
        return announcementPage.convert(announcement -> convertToVO(announcement, readIds.contains(announcement.getId())));
    }

    @Override
    public IPage<AnnouncementVO> getAdminList(Page<Announcement> page) {
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getCreateTime);

        IPage<Announcement> announcementPage = this.page(page, wrapper);
        return announcementPage.convert(announcement -> convertToVO(announcement, null));
    }

    @Override
    public AnnouncementVO getDetail(Long userId, Long announcementId) {
        Announcement announcement = this.getById(announcementId);
        if (announcement == null) {
            throw new GeneralException(ResultEnum.ANNOUNCEMENT_NOT_FOUND);
        }

        // 检查用户是否已读
        boolean isRead = false;
        if (userId != null) {
            Long count = announcementReadMapper.selectCount(
                    new LambdaQueryWrapper<AnnouncementRead>()
                            .eq(AnnouncementRead::getAnnouncementId, announcementId)
                            .eq(AnnouncementRead::getUserId, userId)
            );
            isRead = count > 0;
        }

        return convertToVO(announcement, isRead);
    }

    @Override
    public int getUnreadCount(Long userId) {
        return announcementReadMapper.countUnreadAnnouncements(userId);
    }

    @Override
    @Transactional
    public void markAsRead(Long userId, Long announcementId) {
        // 检查是否已存在已读记录
        Long count = announcementReadMapper.selectCount(
                new LambdaQueryWrapper<AnnouncementRead>()
                        .eq(AnnouncementRead::getAnnouncementId, announcementId)
                        .eq(AnnouncementRead::getUserId, userId)
        );

        if (count == 0) {
            AnnouncementRead read = new AnnouncementRead()
                    .setAnnouncementId(announcementId)
                    .setUserId(userId)
                    .setReadTime(new Date());
            announcementReadMapper.insert(read);
        }
    }

    @Override
    @Transactional
    public void markAllAsRead(Long userId) {
        // 获取所有已发布且未读的公告
        Set<Long> readIds = announcementReadMapper.selectReadAnnouncementIds(userId);

        List<Announcement> unreadAnnouncements = this.list(
                new LambdaQueryWrapper<Announcement>()
                        .eq(Announcement::getStatus, AnnouncementStatus.PUBLISHED.getCode())
                        .notIn(!readIds.isEmpty(), Announcement::getId, readIds)
        );

        // 批量插入已读记录
        Date now = new Date();
        for (Announcement announcement : unreadAnnouncements) {
            AnnouncementRead read = new AnnouncementRead()
                    .setAnnouncementId(announcement.getId())
                    .setUserId(userId)
                    .setReadTime(now);
            announcementReadMapper.insert(read);
        }

        log.info("用户 {} 已将 {} 条公告标记为已读", userId, unreadAnnouncements.size());
    }

    @Override
    @Transactional
    public void deleteAnnouncement(Long announcementId) {
        this.removeById(announcementId);
        log.info("公告删除成功: id={}", announcementId);
    }

    @Override
    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    @Transactional
    public void processScheduledAnnouncements() {
        Date now = new Date();
        List<Announcement> scheduledAnnouncements = baseMapper.selectScheduledAnnouncements(now);

        for (Announcement announcement : scheduledAnnouncements) {
            announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
            this.updateById(announcement);
            log.info("定时公告已发布: id={}, title={}", announcement.getId(), announcement.getTitle());

            // 广播给所有在线用户
            broadcastNewAnnouncement(announcement);
        }
    }

    @Override
    @Scheduled(fixedRate = 300000) // 每5分钟执行一次
    @Transactional
    public void processExpiredAnnouncements() {
        Date now = new Date();
        List<Announcement> expiredAnnouncements = baseMapper.selectExpiredAnnouncements(now);

        for (Announcement announcement : expiredAnnouncements) {
            announcement.setStatus(AnnouncementStatus.EXPIRED.getCode());
            this.updateById(announcement);
            log.info("公告已过期: id={}, title={}", announcement.getId(), announcement.getTitle());
        }
    }

    /**
     * 转换为 VO
     */
    private AnnouncementVO convertToVO(Announcement announcement, Boolean isRead) {
        AnnouncementVO vo = new AnnouncementVO()
                .setId(IdUtils.toExternalId(announcement.getId()))
                .setTitle(announcement.getTitle())
                .setContent(announcement.getContent())
                .setPriorityWithDesc(announcement.getPriority())
                .setPinned(announcement.getIsPinned() == 1)
                .setPublishTime(announcement.getPublishTime())
                .setExpireTime(announcement.getExpireTime())
                .setStatusWithDesc(announcement.getStatus())
                .setPublisherId(IdUtils.toExternalId(announcement.getPublisherId()))
                .setCreateTime(announcement.getCreateTime())
                .setRead(isRead);

        // 获取发布者名称
        try {
            var publisher = accountService.findAccountById(announcement.getPublisherId());
            if (publisher != null) {
                vo.setPublisherName(publisher.getUsername());
            }
        } catch (Exception e) {
            log.warn("获取公告发布者信息失败: publisherId={}", announcement.getPublisherId());
        }

        return vo;
    }

    /**
     * 广播新公告给所有在线用户
     */
    private void broadcastNewAnnouncement(Announcement announcement) {
        sseEmitterManager.broadcast(SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, Map.of(
                "id", IdUtils.toExternalId(announcement.getId()),
                "title", announcement.getTitle(),
                "priority", announcement.getPriority(),
                "isPinned", announcement.getIsPinned() == 1
        )));
    }
}
