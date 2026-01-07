package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FriendFileShareMapper;
import cn.flying.dao.vo.friend.FriendShareVO;
import cn.flying.service.AccountService;
import cn.flying.service.FriendService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("FriendFileShareServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class FriendFileShareServiceImplTest {

    @Mock
    private FriendFileShareMapper friendFileShareMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FriendService friendService;

    @Mock
    private AccountService accountService;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    @InjectMocks
    private FriendFileShareServiceImpl friendFileShareService;

    private static final Long SHARER_ID = 1001L;
    private static final Long FRIEND_ID = 1002L;
    private static final Long TENANT_ID = 1L;
    private static final Long SHARE_ID = 5001L;
    private static final String FILE_HASH_1 = "sha256_hash_1";
    private static final String FILE_HASH_2 = "sha256_hash_2";

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(friendFileShareService, "baseMapper", friendFileShareMapper);
        
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::requireTenantId).thenReturn(TENANT_ID);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);
        
        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(inv -> {
            String externalId = inv.getArgument(0);
            return Long.parseLong(externalId.replace("ext_", ""));
        });
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return "ext_" + id;
        });
    }

    @AfterEach
    void tearDown() {
        if (tenantContextMock != null) tenantContextMock.close();
        if (idUtilsMock != null) idUtilsMock.close();
    }

    @Nested
    @DisplayName("shareToFriend")
    class ShareToFriend {

        @Test
        @DisplayName("should share files successfully - validates inputs and creates share")
        void shareToFriend_success() {
            FriendShareVO vo = createShareVO();
            Account friend = createAccount(FRIEND_ID, "friend");
            Account sharer = createAccount(SHARER_ID, "sharer");

            when(friendService.areFriends(SHARER_ID, FRIEND_ID)).thenReturn(true);
            when(accountService.findAccountById(FRIEND_ID)).thenReturn(friend);
            when(accountService.findAccountById(SHARER_ID)).thenReturn(sharer);
            when(fileMapper.selectCount(any())).thenReturn(2L);

            ArgumentCaptor<FriendFileShare> shareCaptor = ArgumentCaptor.forClass(FriendFileShare.class);
            doAnswer(inv -> {
                FriendFileShare share = inv.getArgument(0);
                share.setId(SHARE_ID);
                return true;
            }).when(friendFileShareService).save(shareCaptor.capture());

            assertDoesNotThrow(() -> friendFileShareService.shareToFriend(SHARER_ID, vo));

            FriendFileShare captured = shareCaptor.getValue();
            assertEquals(SHARER_ID, captured.getSharerId());
            assertEquals(FRIEND_ID, captured.getFriendId());
            assertEquals(FriendFileShare.STATUS_ACTIVE, captured.getStatus());
            assertEquals(0, captured.getIsRead());
            verify(sseEmitterManager).sendToUser(eq(TENANT_ID), eq(FRIEND_ID), any(SseEvent.class));
        }

        @Test
        @DisplayName("should throw when not friends")
        void shareToFriend_notFriends_throws() {
            FriendShareVO vo = createShareVO();
            when(friendService.areFriends(SHARER_ID, FRIEND_ID)).thenReturn(false);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.shareToFriend(SHARER_ID, vo));

            assertEquals(ResultEnum.NOT_FRIENDS, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when friend does not exist")
        void shareToFriend_friendNotExist_throws() {
            FriendShareVO vo = createShareVO();
            when(friendService.areFriends(SHARER_ID, FRIEND_ID)).thenReturn(true);
            when(accountService.findAccountById(FRIEND_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.shareToFriend(SHARER_ID, vo));

            assertEquals(ResultEnum.USER_NOT_EXIST, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when sharer does not own all files")
        void shareToFriend_notOwner_throws() {
            FriendShareVO vo = createShareVO();
            Account friend = createAccount(FRIEND_ID, "friend");
            
            when(friendService.areFriends(SHARER_ID, FRIEND_ID)).thenReturn(true);
            when(accountService.findAccountById(FRIEND_ID)).thenReturn(friend);
            when(fileMapper.selectCount(any())).thenReturn(1L);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.shareToFriend(SHARER_ID, vo));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, ex.getResultEnum());
        }

        @Test
        @DisplayName("should deduplicate file hashes")
        void shareToFriend_deduplicatesHashes() throws Exception {
            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId("ext_" + FRIEND_ID);
            vo.setFileHashes(List.of(FILE_HASH_1, FILE_HASH_1, FILE_HASH_2));

            Account friend = createAccount(FRIEND_ID, "friend");
            Account sharer = createAccount(SHARER_ID, "sharer");

            when(friendService.areFriends(SHARER_ID, FRIEND_ID)).thenReturn(true);
            when(accountService.findAccountById(FRIEND_ID)).thenReturn(friend);
            when(accountService.findAccountById(SHARER_ID)).thenReturn(sharer);
            when(fileMapper.selectCount(any())).thenReturn(2L);

            ArgumentCaptor<FriendFileShare> shareCaptor = ArgumentCaptor.forClass(FriendFileShare.class);
            doAnswer(inv -> {
                FriendFileShare share = inv.getArgument(0);
                share.setId(SHARE_ID);
                return true;
            }).when(friendFileShareService).save(shareCaptor.capture());

            assertDoesNotThrow(() -> friendFileShareService.shareToFriend(SHARER_ID, vo));

            // Verify JSON is properly formatted and contains expected hashes
            String fileHashesJson = shareCaptor.getValue().getFileHashes();
            String[] parsedHashes = objectMapper.readValue(fileHashesJson, String[].class);
            List<String> hashesList = Arrays.asList(parsedHashes);
            assertEquals(2, hashesList.size(), "Should contain exactly 2 unique hashes");
            assertTrue(hashesList.contains(FILE_HASH_1), "Should contain FILE_HASH_1");
            assertTrue(hashesList.contains(FILE_HASH_2), "Should contain FILE_HASH_2");
        }
    }

    @Nested
    @DisplayName("cancelShare")
    class CancelShare {

        @Test
        @DisplayName("should cancel share successfully")
        void cancelShare_success() {
            FriendFileShare share = createActiveShare();
            doReturn(share).when(friendFileShareService).getById(SHARE_ID);
            doReturn(true).when(friendFileShareService).updateById(any(FriendFileShare.class));

            assertDoesNotThrow(() -> friendFileShareService.cancelShare(SHARER_ID, "ext_" + SHARE_ID));

            assertEquals(FriendFileShare.STATUS_CANCELLED, share.getStatus());
        }

        @Test
        @DisplayName("should throw when share not found")
        void cancelShare_notFound_throws() {
            doReturn(null).when(friendFileShareService).getById(SHARE_ID);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.cancelShare(SHARER_ID, "ext_" + SHARE_ID));

            assertEquals(ResultEnum.FRIEND_SHARE_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when user is not sharer")
        void cancelShare_notSharer_throws() {
            FriendFileShare share = createActiveShare();
            share.setSharerId(9999L);
            doReturn(share).when(friendFileShareService).getById(SHARE_ID);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.cancelShare(SHARER_ID, "ext_" + SHARE_ID));

            assertEquals(ResultEnum.FRIEND_SHARE_UNAUTHORIZED, ex.getResultEnum());
        }
    }

    @Nested
    @DisplayName("getShareDetail")
    class GetShareDetail {

        @Test
        @DisplayName("should throw when share not found")
        void getShareDetail_notFound_throws() {
            doReturn(null).when(friendFileShareService).getById(SHARE_ID);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.getShareDetail(SHARER_ID, "ext_" + SHARE_ID));

            assertEquals(ResultEnum.FRIEND_SHARE_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when user not authorized")
        void getShareDetail_unauthorized_throws() {
            FriendFileShare share = createActiveShare();
            share.setSharerId(8888L);
            share.setFriendId(9999L);
            doReturn(share).when(friendFileShareService).getById(SHARE_ID);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.getShareDetail(SHARER_ID, "ext_" + SHARE_ID));

            assertEquals(ResultEnum.FRIEND_SHARE_UNAUTHORIZED, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when cancelled share viewed by receiver")
        void getShareDetail_cancelledViewedByReceiver_throws() {
            FriendFileShare share = createActiveShare();
            share.setStatus(FriendFileShare.STATUS_CANCELLED);
            doReturn(share).when(friendFileShareService).getById(SHARE_ID);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendFileShareService.getShareDetail(FRIEND_ID, "ext_" + SHARE_ID));

            assertEquals(ResultEnum.FRIEND_SHARE_NOT_FOUND, ex.getResultEnum());
        }
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("should mark share as read")
        void markAsRead_success() {
            when(friendFileShareMapper.markAsRead(eq(SHARE_ID), eq(FRIEND_ID), any(), eq(TENANT_ID)))
                    .thenReturn(1);

            assertDoesNotThrow(() -> friendFileShareService.markAsRead(FRIEND_ID, "ext_" + SHARE_ID));

            verify(friendFileShareMapper).markAsRead(eq(SHARE_ID), eq(FRIEND_ID), any(), eq(TENANT_ID));
        }

        @Test
        @DisplayName("should handle already read share")
        void markAsRead_alreadyRead() {
            when(friendFileShareMapper.markAsRead(eq(SHARE_ID), eq(FRIEND_ID), any(), eq(TENANT_ID)))
                    .thenReturn(0);

            assertDoesNotThrow(() -> friendFileShareService.markAsRead(FRIEND_ID, "ext_" + SHARE_ID));
        }
    }

    @Nested
    @DisplayName("getUnreadCount")
    class GetUnreadCount {

        @Test
        @DisplayName("should return unread count")
        void getUnreadCount_success() {
            when(friendFileShareMapper.countUnread(FRIEND_ID, TENANT_ID)).thenReturn(5);

            int count = friendFileShareService.getUnreadCount(FRIEND_ID);

            assertEquals(5, count);
        }

        @Test
        @DisplayName("should return zero when no unread")
        void getUnreadCount_zero() {
            when(friendFileShareMapper.countUnread(FRIEND_ID, TENANT_ID)).thenReturn(0);

            int count = friendFileShareService.getUnreadCount(FRIEND_ID);

            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("getSharerIdForFile")
    class GetSharerIdForFile {

        @Test
        @DisplayName("should return sharer id when file is shared")
        void getSharerIdForFile_success() {
            when(friendFileShareMapper.findSharerIdForFile(FRIEND_ID, FILE_HASH_1, TENANT_ID))
                    .thenReturn(SHARER_ID);

            Long sharerId = friendFileShareService.getSharerIdForFile(FRIEND_ID, FILE_HASH_1);

            assertEquals(SHARER_ID, sharerId);
        }

        @Test
        @DisplayName("should return null when file not shared")
        void getSharerIdForFile_notShared() {
            when(friendFileShareMapper.findSharerIdForFile(FRIEND_ID, FILE_HASH_1, TENANT_ID))
                    .thenReturn(null);

            Long sharerId = friendFileShareService.getSharerIdForFile(FRIEND_ID, FILE_HASH_1);

            assertNull(sharerId);
        }
    }

    private FriendShareVO createShareVO() {
        FriendShareVO vo = new FriendShareVO();
        vo.setFriendId("ext_" + FRIEND_ID);
        vo.setFileHashes(List.of(FILE_HASH_1, FILE_HASH_2));
        vo.setMessage("Check out these files!");
        return vo;
    }

    private Account createAccount(Long id, String username) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setAvatar("/avatars/default.png");
        account.setTenantId(TENANT_ID);
        return account;
    }

    private FriendFileShare createActiveShare() {
        return new FriendFileShare()
                .setId(SHARE_ID)
                .setSharerId(SHARER_ID)
                .setFriendId(FRIEND_ID)
                .setFileHashes("[\"" + FILE_HASH_1 + "\"]")
                .setStatus(FriendFileShare.STATUS_ACTIVE)
                .setIsRead(0);
    }
}
