package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.mapper.*;
import cn.flying.dao.vo.friend.FriendFileShareDetailVO;
import cn.flying.dao.vo.friend.FriendShareVO;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FriendFileShareController 集成测试
 *
 * 测试好友文件分享的各种场景
 */
@Transactional
@DisplayName("FriendFileShareController Integration Tests")
@TestPropertySource(properties = "test.context=FriendFileShareControllerIntegrationTest")
class FriendFileShareControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/friend-shares";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FriendFileShareMapper friendFileShareMapper;

    @Autowired
    private FriendshipMapper friendshipMapper;

    @Autowired
    private FriendRequestMapper friendRequestMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;
    private Account friendAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId, "shareuser");
        friendAccount = createTestAccount(200L, testTenantId, "frienduser");
    }

    private Account createTestAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username + "_" + userId);
        account.setEmail(username + "_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Test " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    private File createTestFile(Long userId, Long tenantId, String fileHash) {
        File file = new File();
        file.setUid(userId);
        file.setFileName("test_file_" + fileHash + ".txt");
        file.setFileHash(fileHash);
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(tenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> fileMapper.insert(file));
        return file;
    }

    private Friendship createFriendship(Long userId1, Long userId2, Long tenantId) {
        FriendRequest request = createFriendRequest(userId1, userId2, tenantId, FriendRequest.STATUS_ACCEPTED);
        Friendship friendship = Friendship.create(userId1, userId2, request.getId());
        friendship.setTenantId(tenantId);
        friendship.setCreateTime(new Date());
        friendship.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> friendshipMapper.insert(friendship));
        return friendship;
    }

    private FriendRequest createFriendRequest(Long requesterId, Long addresseeId, Long tenantId, Integer status) {
        FriendRequest request = new FriendRequest();
        request.setRequesterId(requesterId);
        request.setAddresseeId(addresseeId);
        request.setMessage("Hello, I want to be friends");
        request.setStatus(status);
        request.setTenantId(tenantId);
        request.setCreateTime(new Date());
        request.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> friendRequestMapper.insert(request));
        return request;
    }

    /**
     * 构造并写入一条好友分享记录（用于集成测试准备数据）。
     *
     * @param sharerId 分享者用户ID（内部ID）
     * @param friendId 接收者用户ID（内部ID）
     * @param tenantId 租户ID
     * @param fileHashes 文件哈希 JSON 字符串（如：["sha256_xxx"]）
     * @param status 分享状态
     * @param isRead 是否已读（0/1）
     * @return 写入后的好友分享实体
     */
    private FriendFileShare createFriendFileShare(Long sharerId, Long friendId, Long tenantId,
                                                   String fileHashes, Integer status, Integer isRead) {
        FriendFileShare share = new FriendFileShare();
        share.setSharerId(sharerId);
        share.setFriendId(friendId);
        share.setTenantId(tenantId);
        share.setFileHashes(fileHashes);
        share.setMessage("Check this file!");
        share.setStatus(status);
        share.setIsRead(isRead);
        share.setCreateTime(new Date());
        TenantContext.runWithTenant(tenantId, () -> friendFileShareMapper.insert(share));
        return share;
    }

    @Nested
    @DisplayName("POST / - Share To Friend")
    class ShareToFriendTests {

        @Test
        @DisplayName("should share file to friend successfully")
        void shouldShareFileToFriendSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_share_test_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId(IdUtils.toExternalId(friendAccount.getId()));
            vo.setFileHashes(List.of(fileHash));
            vo.setMessage("Here's a file for you!");

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sharerId").exists())
                    .andExpect(jsonPath("$.data.friendId").exists())
                    .andExpect(jsonPath("$.data.message").value("Here's a file for you!"));
        }

        @Test
        @DisplayName("should fail to share file to non-friend")
        void shouldFailToShareFileToNonFriend() throws Exception {
            Account nonFriend = createTestAccount(300L, testTenantId, "nonfriend");
            String fileHash = "sha256_nonfriend_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId(IdUtils.toExternalId(nonFriend.getId()));
            vo.setFileHashes(List.of(fileHash));

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60010)); // NOT_FRIENDS
        }

        @Test
        @DisplayName("should fail when file does not belong to user")
        void shouldFailWhenFileDoesNotBelongToUser() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_other_user_" + System.currentTimeMillis();
            // Create file owned by friend, not test user
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId(IdUtils.toExternalId(friendAccount.getId()));
            vo.setFileHashes(List.of(fileHash));

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50008)); // FILE_NOT_EXIST
        }

        @Test
        @DisplayName("should fail for empty file list")
        void shouldFailForEmptyFileList() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);

            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId(IdUtils.toExternalId(friendAccount.getId()));
            vo.setFileHashes(List.of());

            performPost(BASE_URL, vo)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should share multiple files to friend")
        void shouldShareMultipleFilesToFriend() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash1 = "sha256_multi_1_" + System.currentTimeMillis();
            String fileHash2 = "sha256_multi_2_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash1);
            createTestFile(testUserId, testTenantId, fileHash2);

            FriendShareVO vo = new FriendShareVO();
            vo.setFriendId(IdUtils.toExternalId(friendAccount.getId()));
            vo.setFileHashes(List.of(fileHash1, fileHash2));
            vo.setMessage("Multiple files for you!");

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /received - Get Received Shares")
    class GetReceivedSharesTests {

        @Test
        @DisplayName("should return received shares successfully")
        void shouldReturnReceivedSharesSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_received_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Friend shares file to test user
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performGet(BASE_URL + "/received?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should return empty list when no received shares")
        void shouldReturnEmptyListWhenNoReceivedShares() throws Exception {
            performGet(BASE_URL + "/received?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }

        @Test
        @DisplayName("should not return cancelled shares")
        void shouldNotReturnCancelledShares() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_cancelled_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Friend shares file but then cancels
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_CANCELLED, 0);

            performGet(BASE_URL + "/received?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /sent - Get Sent Shares")
    class GetSentSharesTests {

        @Test
        @DisplayName("should return sent shares successfully")
        void shouldReturnSentSharesSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_sent_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            // Test user shares file to friend
            createFriendFileShare(testUserId, friendAccount.getId(), testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performGet(BASE_URL + "/sent?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should return empty list when no sent shares")
        void shouldReturnEmptyListWhenNoSentShares() throws Exception {
            performGet(BASE_URL + "/sent?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /{shareId} - Get Share Detail")
    class GetShareDetailTests {

        @Test
        @DisplayName("should return share detail for sender")
        void shouldReturnShareDetailForSender() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_detail_sender_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            FriendFileShare share = createFriendFileShare(testUserId, friendAccount.getId(), testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performGet(BASE_URL + "/" + IdUtils.toExternalId(share.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sharerId").exists())
                    .andExpect(jsonPath("$.data.friendId").exists());
        }

        @Test
        @DisplayName("should return share detail for recipient")
        void shouldReturnShareDetailForRecipient() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_detail_recipient_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Friend shares to test user
            FriendFileShare share = createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performGet(BASE_URL + "/" + IdUtils.toExternalId(share.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should fail for unauthorized user")
        void shouldFailForUnauthorizedUser() throws Exception {
            // Create third user
            Account thirdUser = createTestAccount(300L, testTenantId, "thirduser");
            createFriendship(thirdUser.getId(), friendAccount.getId(), testTenantId);

            String fileHash = "sha256_unauthorized_" + System.currentTimeMillis();
            createTestFile(thirdUser.getId(), testTenantId, fileHash);

            // Third user shares to friend (not to test user)
            FriendFileShare share = createFriendFileShare(thirdUser.getId(), friendAccount.getId(), testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            // Test user tries to access
            performGet(BASE_URL + "/" + IdUtils.toExternalId(share.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60017)); // FRIEND_SHARE_UNAUTHORIZED
        }
    }

    @Nested
    @DisplayName("POST /{shareId}/read - Mark As Read")
    class MarkAsReadTests {

        @Test
        @DisplayName("should mark share as read successfully")
        void shouldMarkShareAsReadSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_mark_read_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            FriendFileShare share = createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performPost(BASE_URL + "/" + IdUtils.toExternalId(share.getId()) + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should be idempotent when marking as read multiple times")
        void shouldBeIdempotentWhenMarkingAsReadMultipleTimes() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_idempotent_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            FriendFileShare share = createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);
            String externalId = IdUtils.toExternalId(share.getId());

            // First call
            performPost(BASE_URL + "/" + externalId + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // Second call - should also succeed
            performPost(BASE_URL + "/" + externalId + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should not allow sender to mark as read")
        void shouldNotAllowSenderToMarkAsRead() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_sender_read_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            // Test user is the sender
            FriendFileShare share = createFriendFileShare(testUserId, friendAccount.getId(), testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performPost(BASE_URL + "/" + IdUtils.toExternalId(share.getId()) + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60017)); // FRIEND_SHARE_UNAUTHORIZED
        }
    }

    @Nested
    @DisplayName("DELETE /{shareId} - Cancel Share")
    class CancelShareTests {

        @Test
        @DisplayName("should cancel share successfully by sender")
        void shouldCancelShareSuccessfullyBySender() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_cancel_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            // 通过真实接口创建分享，避免直接插入记录与租户拦截/自动填充行为不一致导致查询不到（CI 偶发 60016）
            FriendShareVO shareVO = new FriendShareVO();
            shareVO.setFriendId(IdUtils.toExternalId(friendAccount.getId()));
            shareVO.setFileHashes(List.of(fileHash));
            shareVO.setMessage("cancel-test");

            FriendFileShareDetailVO created = extractData(
                    performPost(BASE_URL, shareVO)
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.code").value(200))
                            .andReturn(),
                    FriendFileShareDetailVO.class
            );

            performDelete(BASE_URL + "/" + created.getId())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should fail to cancel share by recipient")
        void shouldFailToCancelShareByRecipient() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_cancel_recipient_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Friend is the sender
            FriendFileShare share = createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            // Test user (recipient) tries to cancel
            performDelete(BASE_URL + "/" + IdUtils.toExternalId(share.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60017)); // FRIEND_SHARE_UNAUTHORIZED
        }

        @Test
        @DisplayName("should handle cancelling already cancelled share")
        void shouldHandleCancellingAlreadyCancelledShare() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_double_cancel_" + System.currentTimeMillis();
            createTestFile(testUserId, testTenantId, fileHash);

            FriendFileShare share = createFriendFileShare(testUserId, friendAccount.getId(), testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_CANCELLED, 0);

            performDelete(BASE_URL + "/" + IdUtils.toExternalId(share.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200)); // Idempotent: already cancelled returns success
        }
    }

    @Nested
    @DisplayName("GET /unread-count - Get Unread Count")
    class GetUnreadCountTests {

        @Test
        @DisplayName("should return correct unread count")
        void shouldReturnCorrectUnreadCount() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);

            String fileHash1 = "sha256_unread_1_" + System.currentTimeMillis();
            String fileHash2 = "sha256_unread_2_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash1);
            createTestFile(friendAccount.getId(), testTenantId, fileHash2);

            // Create 2 unread shares
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash1 + "\"]", FriendFileShare.STATUS_ACTIVE, 0);
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash2 + "\"]", FriendFileShare.STATUS_ACTIVE, 0);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(2));
        }

        @Test
        @DisplayName("should return zero for no unread shares")
        void shouldReturnZeroForNoUnreadShares() throws Exception {
            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        @DisplayName("should not count read shares")
        void shouldNotCountReadShares() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_read_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Create a read share
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_ACTIVE, 1);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        @DisplayName("should not count cancelled shares")
        void shouldNotCountCancelledShares() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String fileHash = "sha256_cancelled_count_" + System.currentTimeMillis();
            createTestFile(friendAccount.getId(), testTenantId, fileHash);

            // Create a cancelled unread share
            createFriendFileShare(friendAccount.getId(), testUserId, testTenantId,
                    "[\"" + fileHash + "\"]", FriendFileShare.STATUS_CANCELLED, 0);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(0));
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/received")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Pagination Tests")
    class PaginationTests {

        @Test
        @DisplayName("should handle pagination parameters correctly")
        void shouldHandlePaginationParametersCorrectly() throws Exception {
            performGet(BASE_URL + "/received?pageNum=2&pageSize=5")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.current").value(2))
                    .andExpect(jsonPath("$.data.size").value(5));
        }

        @Test
        @DisplayName("should use default pagination when not specified")
        void shouldUseDefaultPaginationWhenNotSpecified() throws Exception {
            performGet(BASE_URL + "/received")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.current").value(1))
                    .andExpect(jsonPath("$.data.size").value(20));
        }
    }
}
