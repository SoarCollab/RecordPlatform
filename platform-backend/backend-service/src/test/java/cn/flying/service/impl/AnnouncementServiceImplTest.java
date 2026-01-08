package cn.flying.service.impl;

import cn.flying.common.constant.AnnouncementStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.entity.AnnouncementRead;
import cn.flying.dao.mapper.AnnouncementMapper;
import cn.flying.dao.mapper.AnnouncementReadMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.service.AccountService;
import cn.flying.service.sse.SseEmitterManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AnnouncementServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceImplTest {

    @Mock
    private AnnouncementMapper announcementMapper;

    @Mock
    private AnnouncementReadMapper announcementReadMapper;

    @Mock
    private AccountService accountService;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Spy
    @InjectMocks
    private AnnouncementServiceImpl announcementService;

    private static final Long PUBLISHER_ID = 1001L;
    private static final Long USER_ID = 2001L;
    private static final Long ANNOUNCEMENT_ID = 3001L;
    private static final Long TENANT_ID = 1L;

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(announcementService, "baseMapper", announcementMapper);

        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);

        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong())).thenAnswer(inv -> "ext_" + inv.getArgument(0));
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(inv -> {
            String externalId = inv.getArgument(0);
            return Long.parseLong(externalId.replace("ext_", ""));
        });
    }

    @AfterEach
    void tearDown() {
        if (tenantContextMock != null) tenantContextMock.close();
        if (idUtilsMock != null) idUtilsMock.close();
    }

    @Nested
    @DisplayName("publish")
    class Publish {

        @Test
        @DisplayName("should create draft announcement when status is 0")
        void publish_draft() {
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setStatus(0);

            doAnswer(inv -> {
                Announcement announcement = inv.getArgument(0);
                announcement.setId(ANNOUNCEMENT_ID);
                return true;
            }).when(announcementService).save(any(Announcement.class));

            Announcement result = announcementService.publish(PUBLISHER_ID, vo);

            assertNotNull(result);
            assertEquals(AnnouncementStatus.DRAFT.getCode(), result.getStatus());
            assertEquals(PUBLISHER_ID, result.getPublisherId());
            verify(sseEmitterManager, never()).broadcastToTenant(anyLong(), any());
        }

        @Test
        @DisplayName("should publish immediately when publishTime is null")
        void publish_immediately() {
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setStatus(1);
            vo.setPublishTime(null);

            doAnswer(inv -> {
                Announcement announcement = inv.getArgument(0);
                announcement.setId(ANNOUNCEMENT_ID);
                return true;
            }).when(announcementService).save(any(Announcement.class));

            Announcement result = announcementService.publish(PUBLISHER_ID, vo);

            assertNotNull(result);
            assertEquals(AnnouncementStatus.PUBLISHED.getCode(), result.getStatus());
            assertNotNull(result.getPublishTime());
        }

        @Test
        @DisplayName("should publish immediately when publishTime is in the past")
        void publish_pastTime() {
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setStatus(1);
            vo.setPublishTime(new Date(System.currentTimeMillis() - 3600000)); // 1 hour ago

            doAnswer(inv -> {
                Announcement announcement = inv.getArgument(0);
                announcement.setId(ANNOUNCEMENT_ID);
                return true;
            }).when(announcementService).save(any(Announcement.class));

            Announcement result = announcementService.publish(PUBLISHER_ID, vo);

            assertEquals(AnnouncementStatus.PUBLISHED.getCode(), result.getStatus());
        }

        @Test
        @DisplayName("should schedule announcement when publishTime is in the future")
        void publish_scheduled() {
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setStatus(1);
            vo.setPublishTime(new Date(System.currentTimeMillis() + 3600000)); // 1 hour later

            doAnswer(inv -> {
                Announcement announcement = inv.getArgument(0);
                announcement.setId(ANNOUNCEMENT_ID);
                return true;
            }).when(announcementService).save(any(Announcement.class));

            Announcement result = announcementService.publish(PUBLISHER_ID, vo);

            assertEquals(AnnouncementStatus.DRAFT.getCode(), result.getStatus());
        }

        @Test
        @DisplayName("should set all fields from VO")
        void publish_allFields() {
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setPriority(2);
            vo.setIsPinned(1);

            ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
            doAnswer(inv -> {
                Announcement announcement = inv.getArgument(0);
                announcement.setId(ANNOUNCEMENT_ID);
                return true;
            }).when(announcementService).save(captor.capture());

            announcementService.publish(PUBLISHER_ID, vo);

            Announcement saved = captor.getValue();
            assertEquals("Test Title", saved.getTitle());
            assertEquals("Test Content", saved.getContent());
            assertEquals(2, saved.getPriority());
            assertEquals(1, saved.getIsPinned());
            assertEquals(PUBLISHER_ID, saved.getPublisherId());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("should update announcement successfully")
        void update_success() {
            Announcement existing = createAnnouncement();
            existing.setStatus(AnnouncementStatus.DRAFT.getCode());
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setTitle("Updated Title");
            vo.setStatus(0);

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(existing);
            doReturn(true).when(announcementService).updateById(any(Announcement.class));

            Announcement result = announcementService.update(ANNOUNCEMENT_ID, vo);

            assertEquals("Updated Title", result.getTitle());
            verify(announcementService).updateById(existing);
        }

        @Test
        @DisplayName("should throw when announcement not found")
        void update_notFound() {
            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> announcementService.update(ANNOUNCEMENT_ID, createAnnouncementVO()));

            assertEquals(ResultEnum.ANNOUNCEMENT_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should set publishTime when publishing from draft")
        void update_setPublishTime() {
            Announcement existing = createAnnouncement();
            existing.setStatus(AnnouncementStatus.DRAFT.getCode());
            existing.setPublishTime(null);
            AnnouncementCreateVO vo = createAnnouncementVO();
            vo.setStatus(1);
            vo.setPublishTime(null);

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(existing);
            doReturn(true).when(announcementService).updateById(any(Announcement.class));

            Announcement result = announcementService.update(ANNOUNCEMENT_ID, vo);

            assertEquals(AnnouncementStatus.PUBLISHED.getCode(), result.getStatus());
            assertNotNull(result.getPublishTime());
        }
    }

    @Nested
    @DisplayName("getDetail")
    class GetDetail {

        @Test
        @DisplayName("should return announcement detail with read status")
        void getDetail_withReadStatus() {
            Announcement announcement = createAnnouncement();
            Account publisher = new Account();
            publisher.setId(PUBLISHER_ID);
            publisher.setUsername("admin");

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(announcement);
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            when(accountService.findAccountById(PUBLISHER_ID)).thenReturn(publisher);

            AnnouncementVO result = announcementService.getDetail(USER_ID, ANNOUNCEMENT_ID);

            assertNotNull(result);
            assertTrue(result.getRead());
            assertEquals("admin", result.getAuthor());
        }

        @Test
        @DisplayName("should return unread status when not read")
        void getDetail_unread() {
            Announcement announcement = createAnnouncement();
            Account publisher = new Account();
            publisher.setId(PUBLISHER_ID);
            publisher.setUsername("admin");

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(announcement);
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(accountService.findAccountById(PUBLISHER_ID)).thenReturn(publisher);

            AnnouncementVO result = announcementService.getDetail(USER_ID, ANNOUNCEMENT_ID);

            assertFalse(result.getRead());
        }

        @Test
        @DisplayName("should throw when announcement not found")
        void getDetail_notFound() {
            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> announcementService.getDetail(USER_ID, ANNOUNCEMENT_ID));

            assertEquals(ResultEnum.ANNOUNCEMENT_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should skip read check when userId is null")
        void getDetail_nullUserId() {
            Announcement announcement = createAnnouncement();

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(announcement);
            lenient().when(accountService.findAccountById(PUBLISHER_ID)).thenReturn(null);

            AnnouncementVO result = announcementService.getDetail(null, ANNOUNCEMENT_ID);

            assertFalse(result.getRead());
            verify(announcementReadMapper, never()).selectCount(any());
        }

        @Test
        @DisplayName("should handle missing publisher gracefully")
        void getDetail_noPublisher() {
            Announcement announcement = createAnnouncement();

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(announcement);
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(accountService.findAccountById(PUBLISHER_ID)).thenReturn(null);

            AnnouncementVO result = announcementService.getDetail(USER_ID, ANNOUNCEMENT_ID);

            assertNull(result.getAuthor());
        }

        @Test
        @DisplayName("should handle publisher lookup exception gracefully")
        void getDetail_publisherException() {
            Announcement announcement = createAnnouncement();

            when(announcementService.getById(ANNOUNCEMENT_ID)).thenReturn(announcement);
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(accountService.findAccountById(PUBLISHER_ID)).thenThrow(new RuntimeException("DB error"));

            AnnouncementVO result = announcementService.getDetail(USER_ID, ANNOUNCEMENT_ID);

            assertNull(result.getAuthor());
        }
    }

    @Nested
    @DisplayName("getPublishedList")
    class GetPublishedList {

        @Test
        @DisplayName("should return published announcements with read status")
        @SuppressWarnings("unchecked")
        void getPublishedList_success() {
            Announcement ann1 = createAnnouncement();
            ann1.setId(3001L);
            Announcement ann2 = createAnnouncement();
            ann2.setId(3002L);
            List<Announcement> announcements = List.of(ann1, ann2);

            Page<Announcement> page = new Page<>(1, 10);
            page.setRecords(announcements);
            page.setTotal(2);

            when(announcementReadMapper.selectReadAnnouncementIds(USER_ID)).thenReturn(Set.of(3001L));
            doReturn(page).when(announcementService).page(any(Page.class), any(LambdaQueryWrapper.class));
            lenient().when(accountService.findAccountById(anyLong())).thenReturn(null);

            IPage<AnnouncementVO> result = announcementService.getPublishedList(USER_ID, new Page<>(1, 10));

            assertEquals(2, result.getRecords().size());
            assertTrue(result.getRecords().get(0).getRead());
            assertFalse(result.getRecords().get(1).getRead());
        }
    }

    @Nested
    @DisplayName("getAdminList")
    class GetAdminList {

        @Test
        @DisplayName("should return all announcements for admin")
        @SuppressWarnings("unchecked")
        void getAdminList_success() {
            Announcement ann1 = createAnnouncement();
            ann1.setStatus(AnnouncementStatus.DRAFT.getCode());
            Announcement ann2 = createAnnouncement();
            ann2.setStatus(AnnouncementStatus.PUBLISHED.getCode());
            List<Announcement> announcements = List.of(ann1, ann2);

            Page<Announcement> page = new Page<>(1, 10);
            page.setRecords(announcements);
            page.setTotal(2);

            doReturn(page).when(announcementService).page(any(Page.class), any(LambdaQueryWrapper.class));
            lenient().when(accountService.findAccountById(anyLong())).thenReturn(null);

            IPage<AnnouncementVO> result = announcementService.getAdminList(new Page<>(1, 10));

            assertEquals(2, result.getRecords().size());
        }
    }

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("should return unread count")
        void getUnreadCount_success() {
            when(announcementReadMapper.countUnreadAnnouncements(USER_ID)).thenReturn(5);

            int count = announcementService.getUnreadCount(USER_ID);

            assertEquals(5, count);
        }

        @Test
        @DisplayName("should return zero when no unread")
        void getUnreadCount_zero() {
            when(announcementReadMapper.countUnreadAnnouncements(USER_ID)).thenReturn(0);

            int count = announcementService.getUnreadCount(USER_ID);

            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("should mark announcement as read")
        void markAsRead_success() {
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            when(announcementReadMapper.insert(any(AnnouncementRead.class))).thenReturn(1);

            assertDoesNotThrow(() -> announcementService.markAsRead(USER_ID, ANNOUNCEMENT_ID));

            verify(announcementReadMapper).insert(any(AnnouncementRead.class));
        }

        @Test
        @DisplayName("should not duplicate read record")
        void markAsRead_alreadyRead() {
            when(announcementReadMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            assertDoesNotThrow(() -> announcementService.markAsRead(USER_ID, ANNOUNCEMENT_ID));

            verify(announcementReadMapper, never()).insert(any(AnnouncementRead.class));
        }
    }

    @Nested
    @DisplayName("markAllAsRead")
    class MarkAllAsRead {

        @Test
        @DisplayName("should mark all unread announcements as read")
        @SuppressWarnings("unchecked")
        void markAllAsRead_success() {
            Announcement ann1 = createAnnouncement();
            ann1.setId(3001L);
            Announcement ann2 = createAnnouncement();
            ann2.setId(3002L);

            when(announcementReadMapper.selectReadAnnouncementIds(USER_ID)).thenReturn(Set.of(3001L));
            doReturn(List.of(ann2)).when(announcementService).list(any(LambdaQueryWrapper.class));
            when(announcementReadMapper.insert(any(AnnouncementRead.class))).thenReturn(1);

            assertDoesNotThrow(() -> announcementService.markAllAsRead(USER_ID));

            verify(announcementReadMapper, times(1)).insert(any(AnnouncementRead.class));
        }

        @Test
        @DisplayName("should handle no unread announcements")
        @SuppressWarnings("unchecked")
        void markAllAsRead_noUnread() {
            when(announcementReadMapper.selectReadAnnouncementIds(USER_ID)).thenReturn(Set.of(3001L, 3002L));
            doReturn(List.of()).when(announcementService).list(any(LambdaQueryWrapper.class));

            assertDoesNotThrow(() -> announcementService.markAllAsRead(USER_ID));

            verify(announcementReadMapper, never()).insert(any(AnnouncementRead.class));
        }
    }

    @Nested
    @DisplayName("deleteAnnouncement")
    class DeleteAnnouncement {

        @Test
        @DisplayName("should delete announcement successfully")
        void deleteAnnouncement_success() {
            doReturn(true).when(announcementService).removeById(ANNOUNCEMENT_ID);

            assertDoesNotThrow(() -> announcementService.deleteAnnouncement(ANNOUNCEMENT_ID));

            verify(announcementService).removeById(ANNOUNCEMENT_ID);
        }
    }

    @Nested
    @DisplayName("getLatest")
    class GetLatest {

        @Test
        @DisplayName("should return latest announcements with read status")
        @SuppressWarnings("unchecked")
        void getLatest_success() {
            Announcement ann1 = createAnnouncement();
            ann1.setId(3001L);
            Announcement ann2 = createAnnouncement();
            ann2.setId(3002L);

            when(announcementReadMapper.selectReadAnnouncementIds(USER_ID)).thenReturn(Set.of(3001L));
            doReturn(List.of(ann1, ann2)).when(announcementService).list(any(LambdaQueryWrapper.class));
            lenient().when(accountService.findAccountById(anyLong())).thenReturn(null);

            List<AnnouncementVO> result = announcementService.getLatest(USER_ID, 10);

            assertEquals(2, result.size());
            assertTrue(result.get(0).getRead());
            assertFalse(result.get(1).getRead());
        }

        @Test
        @DisplayName("should use empty read set when userId is null")
        @SuppressWarnings("unchecked")
        void getLatest_nullUserId() {
            Announcement ann = createAnnouncement();

            doReturn(List.of(ann)).when(announcementService).list(any(LambdaQueryWrapper.class));
            lenient().when(accountService.findAccountById(anyLong())).thenReturn(null);

            List<AnnouncementVO> result = announcementService.getLatest(null, 10);

            assertEquals(1, result.size());
            assertFalse(result.get(0).getRead());
            verify(announcementReadMapper, never()).selectReadAnnouncementIds(any());
        }
    }

    private AnnouncementCreateVO createAnnouncementVO() {
        AnnouncementCreateVO vo = new AnnouncementCreateVO();
        vo.setTitle("Test Title");
        vo.setContent("Test Content");
        vo.setPriority(0);
        vo.setIsPinned(0);
        vo.setStatus(1);
        return vo;
    }

    private Announcement createAnnouncement() {
        Announcement announcement = new Announcement();
        announcement.setId(ANNOUNCEMENT_ID);
        announcement.setTitle("Test Title");
        announcement.setContent("Test Content");
        announcement.setPriority(0);
        announcement.setIsPinned(0);
        announcement.setStatus(AnnouncementStatus.PUBLISHED.getCode());
        announcement.setPublisherId(PUBLISHER_ID);
        announcement.setPublishTime(new Date());
        announcement.setCreateTime(new Date());
        return announcement;
    }
}
