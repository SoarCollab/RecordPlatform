package cn.flying.service.impl;

import cn.flying.common.constant.AnnouncementStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.entity.AnnouncementRead;
import cn.flying.dao.mapper.AnnouncementMapper;
import cn.flying.dao.mapper.AnnouncementReadMapper;
import cn.flying.dao.mapper.TenantMapper;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Resource
    private TenantMapper tenantMapper;

    @Resource
    private TransactionTemplate transactionTemplate;

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

        if (vo.getStatus() == 0) {
            announcement.setStatus(AnnouncementStatus.DRAFT.getCode());
        } else if (vo.getPublishTime() == null || vo.getPublishTime().before(new Date())) {
            announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
            announcement.setPublishTime(new Date());
        } else {
            announcement.setStatus(AnnouncementStatus.DRAFT.getCode());
        }

        this.save(announcement);
        log.info("公告发布成功: id={}, title={}, status={}", announcement.getId(), announcement.getTitle(), announcement.getStatus());

        if (announcement.getStatus() == AnnouncementStatus.PUBLISHED.getCode()) {
            broadcastNewAnnouncement(announcement);
        }

        return announcement;
    }

    @Override
    @Transactional
    public Announcement update(Long announcementId, AnnouncementCreateVO vo) {
        Announcement announcement = getById(announcementId);
        if (announcement == null) {
            throw new GeneralException(ResultEnum.ANNOUNCEMENT_NOT_FOUND);
        }

        announcement.setTitle(vo.getTitle())
                .setContent(vo.getContent())
                .setPriority(vo.getPriority())
                .setIsPinned(vo.getIsPinned())
                .setPublishTime(vo.getPublishTime())
                .setExpireTime(vo.getExpireTime());

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
        LambdaQueryWrapper<Announcement> wrapper = new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, AnnouncementStatus.PUBLISHED.getCode())
                .orderByDesc(Announcement::getIsPinned)
                .orderByDesc(Announcement::getPublishTime);

        IPage<Announcement> announcementPage = this.page(page, wrapper);
        Set<Long> readIds = announcementReadMapper.selectReadAnnouncementIds(userId);
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
        Announcement announcement = getById(announcementId);
        if (announcement == null) {
            throw new GeneralException(ResultEnum.ANNOUNCEMENT_NOT_FOUND);
        }

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
        Set<Long> readIds = announcementReadMapper.selectReadAnnouncementIds(userId);

        List<Announcement> unreadAnnouncements = this.list(
                new LambdaQueryWrapper<Announcement>()
                        .eq(Announcement::getStatus, AnnouncementStatus.PUBLISHED.getCode())
                        .notIn(!readIds.isEmpty(), Announcement::getId, readIds)
        );

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
    @Scheduled(fixedRate = 60000)
    public void processScheduledAnnouncements() {
        Date now = new Date();
        tenantMapper.selectActiveTenantIds().forEach(tenantId -> {
            try {
                List<Announcement> published = new ArrayList<>();
                TenantContext.runWithTenant(tenantId, () ->
                        transactionTemplate.executeWithoutResult(status -> {
                            List<Announcement> scheduledAnnouncements = baseMapper.selectScheduledAnnouncements(now);
                            for (Announcement announcement : scheduledAnnouncements) {
                                announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
                                this.updateById(announcement);
                                log.info("定时公告已发布: tenantId={}, id={}, title={}", tenantId, announcement.getId(), announcement.getTitle());
                                published.add(announcement);
                            }
                        })
                );
                // 事务提交后广播
                for (Announcement announcement : published) {
                    sseEmitterManager.broadcastToTenant(tenantId, SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, Map.of(
                            "id", IdUtils.toExternalId(announcement.getId()),
                            "title", announcement.getTitle(),
                            "priority", announcement.getPriority(),
                            "isPinned", announcement.getIsPinned() == 1
                    )));
                }
            } catch (Exception e) {
                log.error("处理定时公告失败: tenantId={}", tenantId, e);
            }
        });
    }

    @Override
    @Scheduled(fixedRate = 300000)
    public void processExpiredAnnouncements() {
        Date now = new Date();
        tenantMapper.selectActiveTenantIds().forEach(tenantId -> {
            try {
                TenantContext.runWithTenant(tenantId, () ->
                        transactionTemplate.executeWithoutResult(status -> {
                            List<Announcement> expiredAnnouncements = baseMapper.selectExpiredAnnouncements(now);
                            for (Announcement announcement : expiredAnnouncements) {
                                announcement.setStatus(AnnouncementStatus.EXPIRED.getCode());
                                this.updateById(announcement);
                                log.info("公告已过期: tenantId={}, id={}, title={}", tenantId, announcement.getId(), announcement.getTitle());
                            }
                        })
                );
            } catch (Exception e) {
                log.error("处理过期公告失败: tenantId={}", tenantId, e);
            }
        });
    }

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

    private void broadcastNewAnnouncement(Announcement announcement) {
        Long tenantId = TenantContext.requireTenantId();
        SseEvent event = SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, Map.of(
                "id", IdUtils.toExternalId(announcement.getId()),
                "title", announcement.getTitle(),
                "priority", announcement.getPriority(),
                "isPinned", announcement.getIsPinned() == 1
        ));

        Runnable pushTask = () -> sseEmitterManager.broadcastToTenant(tenantId, event);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushTask.run();
                }
            });
        } else {
            pushTask.run();
        }
    }
}
