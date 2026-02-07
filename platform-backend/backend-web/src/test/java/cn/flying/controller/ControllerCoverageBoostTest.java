package cn.flying.controller;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.ControllerUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.entity.Message;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.dao.vo.auth.AccountVO;
import cn.flying.dao.vo.auth.ChangePasswordVO;
import cn.flying.dao.vo.auth.ConfirmResetVO;
import cn.flying.dao.vo.auth.EmailRegisterVO;
import cn.flying.dao.vo.auth.EmailResetVO;
import cn.flying.dao.vo.auth.ModifyEmailVO;
import cn.flying.dao.vo.auth.RefreshTokenVO;
import cn.flying.dao.vo.auth.SseTokenVO;
import cn.flying.dao.vo.auth.UpdateUserVO;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.FileUploadStatusVO;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.dao.vo.friend.FriendFileShareDetailVO;
import cn.flying.dao.vo.friend.FriendRequestDetailVO;
import cn.flying.dao.vo.friend.FriendShareVO;
import cn.flying.dao.vo.friend.FriendVO;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.dao.vo.friend.UpdateRemarkVO;
import cn.flying.dao.vo.friend.UserSearchVO;
import cn.flying.dao.vo.message.ConversationDetailVO;
import cn.flying.dao.vo.message.ConversationVO;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.dao.vo.message.SendMessageVO;
import cn.flying.dao.vo.ticket.TicketCreateVO;
import cn.flying.dao.vo.ticket.TicketDetailVO;
import cn.flying.dao.vo.ticket.TicketQueryVO;
import cn.flying.dao.vo.ticket.TicketReplyVO;
import cn.flying.dao.vo.ticket.TicketUpdateVO;
import cn.flying.dao.vo.ticket.TicketVO;
import cn.flying.service.AccountService;
import cn.flying.service.AnnouncementService;
import cn.flying.service.ConversationService;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.FriendService;
import cn.flying.service.MessageService;
import cn.flying.service.PermissionService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.TicketService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller 层覆盖率补齐测试（仅覆盖新接口路径）。
 */
@ExtendWith(MockitoExtension.class)
class ControllerCoverageBoostTest {

    @Mock
    private AccountService accountService;
    @Mock
    private MessageService messageService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private AnnouncementService announcementService;
    @Mock
    private FriendService friendService;
    @Mock
    private TicketService ticketService;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private FileQueryService fileQueryService;
    @Mock
    private FileService fileService;
    @Mock
    private ShareAuditService shareAuditService;
    @Mock
    private FriendFileShareService friendFileShareService;
    @Mock
    private SysPermissionMapper permissionMapper;
    @Mock
    private SysRolePermissionMapper rolePermissionMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private cn.flying.common.util.JwtUtils jwtUtils;

    private AuthorizeController authorizeController;
    private AccountController accountController;
    private MessageController messageController;
    private ConversationController conversationController;
    private AnnouncementController announcementController;
    private FriendController friendController;
    private TicketController ticketController;
    private UploadSessionController uploadSessionController;
    private FileController fileController;
    private FriendFileShareController friendFileShareController;
    private PermissionController permissionController;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234")
        );

        ControllerUtils controllerUtils = new ControllerUtils();

        authorizeController = new AuthorizeController();
        ReflectionTestUtils.setField(authorizeController, "accountService", accountService);
        ReflectionTestUtils.setField(authorizeController, "utils", controllerUtils);
        ReflectionTestUtils.setField(authorizeController, "jwtUtils", jwtUtils);

        accountController = new AccountController();
        ReflectionTestUtils.setField(accountController, "accountService", accountService);
        ReflectionTestUtils.setField(accountController, "utils", controllerUtils);

        messageController = new MessageController();
        ReflectionTestUtils.setField(messageController, "messageService", messageService);

        conversationController = new ConversationController();
        ReflectionTestUtils.setField(conversationController, "conversationService", conversationService);
        ReflectionTestUtils.setField(conversationController, "messageService", messageService);

        announcementController = new AnnouncementController();
        ReflectionTestUtils.setField(announcementController, "announcementService", announcementService);

        friendController = new FriendController();
        ReflectionTestUtils.setField(friendController, "friendService", friendService);

        ticketController = new TicketController();
        ReflectionTestUtils.setField(ticketController, "ticketService", ticketService);

        uploadSessionController = new UploadSessionController();
        ReflectionTestUtils.setField(uploadSessionController, "fileUploadService", fileUploadService);

        fileController = new FileController();
        ReflectionTestUtils.setField(fileController, "fileQueryService", fileQueryService);
        ReflectionTestUtils.setField(fileController, "fileService", fileService);
        ReflectionTestUtils.setField(fileController, "shareAuditService", shareAuditService);

        friendFileShareController = new FriendFileShareController();
        ReflectionTestUtils.setField(friendFileShareController, "friendFileShareService", friendFileShareService);

        permissionController = new PermissionController();
        ReflectionTestUtils.setField(permissionController, "permissionMapper", permissionMapper);
        ReflectionTestUtils.setField(permissionController, "rolePermissionMapper", rolePermissionMapper);
        ReflectionTestUtils.setField(permissionController, "permissionService", permissionService);
    }

    /**
     * 清理 MDC，避免测试间污染。
     */
    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContext.clear();
    }

    /**
     * 覆盖鉴权与用户账户新接口。
     */
    @Test
    void shouldCoverAuthorizeAndAccountControllers() {
        MockHttpServletRequest verifyRequest = new MockHttpServletRequest();
        verifyRequest.setRemoteAddr("127.0.0.1");
        when(accountService.registerEmailVerifyCode(anyString(), anyString(), anyString())).thenReturn(null);

        Result<String> createCodeResult = authorizeController.createVerificationCode("user@test.com", "reset", verifyRequest);
        assertEquals(ResultEnum.SUCCESS.getCode(), createCodeResult.getCode());

        when(accountService.registerEmailAccount(any(EmailRegisterVO.class))).thenReturn(null);
        Result<String> registerResult = authorizeController.register(new EmailRegisterVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), registerResult.getCode());

        when(accountService.resetConfirm(any(ConfirmResetVO.class))).thenReturn(null);
        ConfirmResetVO confirmResetVO = new ConfirmResetVO("user@test.com", "123456");
        Result<String> resetConfirmResult = authorizeController.confirmPasswordReset(confirmResetVO);
        assertEquals(ResultEnum.SUCCESS.getCode(), resetConfirmResult.getCode());

        when(accountService.resetEmailAccountPassword(any(EmailResetVO.class))).thenReturn(null);
        Result<String> resetPasswordRestResult = authorizeController.updatePasswordByReset(new EmailResetVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), resetPasswordRestResult.getCode());

        MockHttpServletRequest refreshRequest = new MockHttpServletRequest();
        refreshRequest.addHeader("Authorization", "Bearer old-token");
        when(jwtUtils.refreshJwt("Bearer old-token")).thenReturn("new-token").thenReturn(null);
        when(jwtUtils.expireTime()).thenReturn(new Date(1700000000000L));
        Result<RefreshTokenVO> refreshSuccess = authorizeController.refreshAccessToken(refreshRequest);
        Result<RefreshTokenVO> refreshExpired = authorizeController.refreshAccessToken(refreshRequest);
        assertEquals(ResultEnum.SUCCESS.getCode(), refreshSuccess.getCode());
        assertEquals("new-token", refreshSuccess.getData().getToken());
        assertEquals(ResultEnum.PERMISSION_TOKEN_EXPIRED.getCode(), refreshExpired.getCode());

        MockHttpServletRequest sseRequest = new MockHttpServletRequest();
        sseRequest.setAttribute(Const.ATTR_USER_ID, 1L);
        sseRequest.setAttribute(Const.ATTR_TENANT_ID, 2L);
        sseRequest.setAttribute(Const.ATTR_USER_ROLE, "user");
        when(jwtUtils.createSseToken(1L, 2L, "user")).thenReturn("sse-token");
        Result<SseTokenVO> sseResult = authorizeController.issueSseToken(sseRequest);
        assertEquals(ResultEnum.SUCCESS.getCode(), sseResult.getCode());
        assertEquals("sse-token", sseResult.getData().getSseToken());

        Account account = new Account();
        account.setId(1L);
        account.setUsername("demo");
        account.setNickname("Demo");
        account.setRole("user");
        account.setTenantId(1L);
        when(accountService.findAccountById(1L)).thenReturn(account);
        Result<AccountVO> infoResult = accountController.getAccountInfo(1L);
        assertEquals(ResultEnum.SUCCESS.getCode(), infoResult.getCode());
        assertEquals("demo", infoResult.getData().getUsername());

        when(accountService.updateUserInfo(eq(1L), any(UpdateUserVO.class))).thenReturn(account);
        Result<AccountVO> updateInfoResult = accountController.updateUserInfo(1L, new UpdateUserVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), updateInfoResult.getCode());

        when(accountService.modifyEmail(eq(1L), any(ModifyEmailVO.class))).thenReturn(null);
        Result<String> modifyEmailRest = accountController.updateEmail(1L, new ModifyEmailVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), modifyEmailRest.getCode());

        when(accountService.changePassword(eq(1L), any(ChangePasswordVO.class))).thenReturn(null);
        Result<String> changePasswordRest = accountController.updatePassword(1L, new ChangePasswordVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), changePasswordRest.getCode());
    }

    /**
     * 覆盖消息与会话新接口。
     */
    @Test
    void shouldCoverMessageAndConversationControllers() {
        Message message = new Message()
                .setId(11L)
                .setSenderId(1L)
                .setReceiverId(2L)
                .setContent("hello")
                .setContentType("text")
                .setCreateTime(new Date(1700000000000L));
        when(messageService.sendMessage(eq(1L), any(SendMessageVO.class))).thenReturn(message);

        SendMessageVO sendMessageVO = new SendMessageVO();
        sendMessageVO.setReceiverId(IdUtils.toExternalId(2L));
        sendMessageVO.setContent("hello");
        sendMessageVO.setContentType("text");

        Result<MessageVO> sendResult = messageController.sendMessage(1L, sendMessageVO);
        assertEquals(ResultEnum.SUCCESS.getCode(), sendResult.getCode());
        assertNotNull(sendResult.getData().getId());

        when(messageService.getTotalUnreadCount(1L)).thenReturn(3);
        Result<Map<String, Integer>> unreadMessageResult = messageController.getUnreadCount(1L);
        assertEquals(3, unreadMessageResult.getData().get("count"));

        IPage<ConversationVO> conversationPage = new Page<>(1, 20);
        when(conversationService.getConversationList(eq(1L), any(Page.class))).thenReturn(conversationPage);
        Result<IPage<ConversationVO>> listResult = conversationController.getList(1L, 1, 20);
        assertEquals(ResultEnum.SUCCESS.getCode(), listResult.getCode());

        String conversationExternalId = IdUtils.toExternalId(88L);
        when(conversationService.getConversationDetail(1L, 88L, 1, 50)).thenReturn(new ConversationDetailVO());
        Result<ConversationDetailVO> detailResult = conversationController.getDetail(1L, conversationExternalId, 1, 50);
        assertEquals(ResultEnum.SUCCESS.getCode(), detailResult.getCode());

        when(conversationService.getUnreadConversationCount(1L)).thenReturn(6);
        Result<Map<String, Integer>> unreadConversationResult = conversationController.getUnreadCount(1L);
        assertEquals(6, unreadConversationResult.getData().get("count"));

        Result<String> readRestResult = conversationController.updateReadStatus(1L, conversationExternalId);
        assertEquals(ResultEnum.SUCCESS.getCode(), readRestResult.getCode());
        verify(messageService).markAsRead(1L, 88L);

        Result<String> deleteResult = conversationController.delete(1L, conversationExternalId);
        assertEquals(ResultEnum.SUCCESS.getCode(), deleteResult.getCode());
        verify(conversationService).deleteConversation(1L, 88L);

        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", null);
        assertThrows(GeneralException.class, () -> conversationController.getDetail(1L, conversationExternalId, 1, 50));
    }

    /**
     * 覆盖公告、好友、工单、上传会话新接口。
     */
    @Test
    void shouldCoverAnnouncementFriendTicketAndUploadControllers() {
        when(announcementService.getLatest(1L, 5)).thenReturn(List.of(new AnnouncementVO()));
        when(announcementService.getPublishedList(eq(1L), any(Page.class))).thenReturn(new Page<>(1, 10));
        when(announcementService.getUnreadCount(1L)).thenReturn(9);
        String announcementExternalId = IdUtils.toExternalId(100L);
        when(announcementService.getDetail(1L, 100L)).thenReturn(new AnnouncementVO());
        when(announcementService.publish(eq(1L), any(AnnouncementCreateVO.class))).thenReturn(new Announcement().setId(100L));
        when(announcementService.update(eq(100L), any(AnnouncementCreateVO.class))).thenReturn(new Announcement().setId(100L));
        when(announcementService.getDetail(null, 100L)).thenReturn(new AnnouncementVO());

        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.getLatest(1L, 5).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.getList(1L, 1, 10).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.getDetail(1L, announcementExternalId).getCode());
        assertEquals(9, announcementController.getUnreadCount(1L).getData().get("count"));
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.updateReadStatus(1L, announcementExternalId).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.updateAllReadStatus(1L).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.publish(1L, new AnnouncementCreateVO()).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.update(announcementExternalId, new AnnouncementCreateVO()).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), announcementController.delete(announcementExternalId).getCode());

        FriendRequest request = new FriendRequest()
                .setId(1L)
                .setRequesterId(1L)
                .setAddresseeId(2L)
                .setMessage("let's be friends")
                .setStatus(0)
                .setCreateTime(new Date(1700000000000L));
        when(friendService.sendRequest(eq(1L), any(SendFriendRequestVO.class))).thenReturn(request);
        when(friendService.getReceivedRequests(eq(1L), any(Page.class))).thenReturn(new Page<FriendRequestDetailVO>(1, 20));
        when(friendService.getSentRequests(eq(1L), any(Page.class))).thenReturn(new Page<FriendRequestDetailVO>(1, 20));
        when(friendService.getPendingCount(1L)).thenReturn(2);
        when(friendService.getFriends(eq(1L), any(Page.class))).thenReturn(new Page<FriendVO>(1, 20));
        when(friendService.getAllFriends(1L)).thenReturn(List.of(new FriendVO()));
        when(friendService.searchUsers(1L, "demo")).thenReturn(List.of(new UserSearchVO()));

        Result<?> sendRequestResult = friendController.sendRequest(1L, new SendFriendRequestVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), sendRequestResult.getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.getReceivedRequests(1L, 1, 20).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.getSentRequests(1L, 1, 20).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.updateRequestStatus(1L, "REQ1", "accept").getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.updateRequestStatus(1L, "REQ1", "rejected").getCode());
        assertThrows(GeneralException.class, () -> friendController.updateRequestStatus(1L, "REQ1", "unknown"));
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.cancelRequest(1L, "REQ1").getCode());
        assertEquals(2, friendController.getPendingCount(1L).getData().get("count"));
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.getFriends(1L, 1, 20).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.getAllFriends(1L).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.unfriend(1L, "FRIEND1").getCode());
        UpdateRemarkVO updateRemarkVO = new UpdateRemarkVO();
        updateRemarkVO.setRemark("teammate");
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.updateRemark(1L, "FRIEND1", updateRemarkVO).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendController.searchUsers(1L, "demo").getCode());

        String ticketExternalId = IdUtils.toExternalId(200L);
        when(ticketService.getUserTickets(eq(1L), any(TicketQueryVO.class), any(Page.class))).thenReturn(new Page<TicketVO>(1, 10));
        when(ticketService.getTicketDetail(eq(1L), eq(200L), anyBoolean())).thenReturn(new TicketDetailVO());
        when(ticketService.createTicket(eq(1L), any(TicketCreateVO.class))).thenReturn(new Ticket().setId(200L));
        when(ticketService.updateTicket(eq(1L), eq(200L), any(TicketUpdateVO.class))).thenReturn(new Ticket().setId(200L));
        when(ticketService.getUserPendingCount(1L)).thenReturn(4);
        when(ticketService.getUserUnreadCount(1L)).thenReturn(7);

        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.getMyTickets(1L, new TicketQueryVO(), 1, 10).getCode());

        MDC.put(Const.ATTR_USER_ROLE, "admin");
        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.getDetail(1L, ticketExternalId).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.reply(1L, ticketExternalId, new TicketReplyVO()).getCode());
        MDC.clear();

        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.create(1L, new TicketCreateVO()).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.update(1L, ticketExternalId, new TicketUpdateVO()).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.close(1L, ticketExternalId).getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), ticketController.confirm(1L, ticketExternalId).getCode());
        assertEquals(4, ticketController.getPendingCount(1L).getData().get("count"));
        assertEquals(7, ticketController.getUnreadCount(1L).getData().get("count"));

        StartUploadVO startUploadVO = new StartUploadVO("client-1", 1024, 4, false, List.of(1), List.of(1), false);
        when(fileUploadService.startUpload(eq(1L), eq("file.txt"), eq(2048L), eq("text/plain"), eq("client-1"), eq(1024), eq(4)))
                .thenReturn(startUploadVO);
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.createUploadSession(
                1L,
                "file.txt",
                2048L,
                "text/plain",
                "client-1",
                1024,
                4
        ).getCode());

        MockMultipartFile chunk = new MockMultipartFile("file", "chunk.bin", "application/octet-stream", "abc".getBytes());
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.uploadChunk(1L, "client-1", 0, chunk).getCode());

        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.completeUpload(1L, "client-1").getCode());
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.pauseUpload(1L, "client-1").getCode());

        when(fileUploadService.resumeUpload(1L, "client-1")).thenReturn(new ResumeUploadVO(List.of(1, 2), 4));
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.resumeUpload(1L, "client-1").getCode());

        when(fileUploadService.cancelUpload(1L, "client-1")).thenReturn(true).thenReturn(false);
        Result<String> cancelSuccess = uploadSessionController.cancelUpload(1L, "client-1");
        Result<String> cancelMissing = uploadSessionController.cancelUpload(1L, "client-1");
        assertEquals(ResultEnum.SUCCESS.getCode(), cancelSuccess.getCode());
        assertEquals(ResultEnum.RESULT_DATA_NONE.getCode(), cancelMissing.getCode());

        FileUploadStatusVO statusVO = new FileUploadStatusVO("file.txt", 2048L, "client-1", false, "UPLOADING", 25, List.of(1), 1, 4);
        when(fileUploadService.checkFileStatus(1L, "client-1")).thenReturn(statusVO);
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.getUploadSession(1L, "client-1").getCode());

        ProgressVO progressVO = new ProgressVO(50, 60, 40, 2, 1, 4, "client-1", "UPLOADING");
        when(fileUploadService.getUploadProgress(1L, "client-1")).thenReturn(progressVO);
        assertEquals(ResultEnum.SUCCESS.getCode(), uploadSessionController.getUploadProgress(1L, "client-1").getCode());
    }

    /**
     * 覆盖文件、好友分享、权限管理控制器的新路径实现。
     */
    @Test
    void shouldCoverFileFriendShareAndPermissionControllers() {
        Long userId = 1L;
        String fileExternalId = IdUtils.toExternalId(10L);

        File file = new File().setId(10L).setFileHash("hash-10");
        when(fileQueryService.getFileById(userId, 10L)).thenReturn(file);
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getFileById(userId, fileExternalId).getCode());

        when(fileQueryService.getUserFileStats(userId)).thenReturn(new UserFileStatsVO(1L, 1024L, 1L, 1L));
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getUserFileStats(userId).getCode());

        when(fileQueryService.getUserShares(eq(userId), any(Page.class))).thenReturn(new Page<FileShareVO>(1, 10));
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getMyShares(userId, 1, 10).getCode());

        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.deleteFiles(userId, List.of("hash-10")).getCode());
        verify(fileService).deleteFiles(userId, List.of("hash-10"));

        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.deleteFileById(List.of("10")).getCode());
        verify(fileService).removeByIds(List.of("10"));

        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.cancelShare(userId, "SHARE10").getCode());
        verify(fileService).cancelShare(userId, "SHARE10");

        when(shareAuditService.getShareAccessLogs(eq("SHARE10"), any(Page.class)))
                .thenReturn(new Page<ShareAccessLogVO>(1, 20));
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getShareAccessLogs("SHARE10", 1, 20).getCode());

        when(shareAuditService.getShareAccessStats("SHARE10"))
                .thenReturn(new ShareAccessStatsVO("SHARE10", 1L, 1L, 1L, 1L, 3L));
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getShareAccessStats("SHARE10").getCode());

        when(shareAuditService.getFileProvenance(10L))
                .thenReturn(new FileProvenanceVO(
                        fileExternalId,
                        "hash-10",
                        "demo.txt",
                        true,
                        "U1",
                        "owner",
                        null,
                        null,
                        0,
                        new Date(1700000000000L),
                        null,
                        List.of()
                ));
        assertEquals(ResultEnum.SUCCESS.getCode(), fileController.getFileProvenance(fileExternalId).getCode());

        FriendShareVO shareVO = new FriendShareVO();
        FriendFileShare friendFileShare = new FriendFileShare().setId(20L);
        when(friendFileShareService.shareToFriend(userId, shareVO)).thenReturn(friendFileShare);
        when(friendFileShareService.getShareDetail(userId, IdUtils.toExternalId(20L))).thenReturn(new FriendFileShareDetailVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.shareToFriend(userId, shareVO).getCode());

        when(friendFileShareService.getReceivedShares(eq(userId), any(Page.class)))
                .thenReturn(new Page<FriendFileShareDetailVO>(1, 20));
        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.getReceivedShares(userId, 1, 20).getCode());

        when(friendFileShareService.getSentShares(eq(userId), any(Page.class)))
                .thenReturn(new Page<FriendFileShareDetailVO>(1, 20));
        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.getSentShares(userId, 1, 20).getCode());

        when(friendFileShareService.getShareDetail(userId, "SHARE20")).thenReturn(new FriendFileShareDetailVO());
        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.getShareDetail(userId, "SHARE20").getCode());

        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.updateReadStatus(userId, "SHARE20").getCode());
        verify(friendFileShareService).markAsRead(userId, "SHARE20");

        assertEquals(ResultEnum.SUCCESS.getCode(), friendFileShareController.cancelShare(userId, "SHARE20").getCode());
        verify(friendFileShareService).cancelShare(userId, "SHARE20");

        when(friendFileShareService.getUnreadCount(userId)).thenReturn(5);
        assertEquals(5, friendFileShareController.getUnreadCount(userId).getData().get("count"));

        TenantContext.setTenantId(1L);
        SysPermission permission = new SysPermission()
                .setId(30L)
                .setCode("system:admin")
                .setName("System Admin")
                .setModule("system")
                .setAction("manage")
                .setStatus(1);

        PermissionController.PermissionCreateVO createVO = new PermissionController.PermissionCreateVO();
        createVO.setCode("perm:create");
        createVO.setName("Create Permission");
        createVO.setModule("permission");
        createVO.setAction("create");
        createVO.setDescription("create desc");
        assertEquals(ResultEnum.SUCCESS.getCode(), permissionController.createPermission(createVO).getCode());

        String permissionExternalId = IdUtils.toExternalId(30L);
        PermissionController.PermissionUpdateVO updateVO = new PermissionController.PermissionUpdateVO();
        updateVO.setName("Updated Permission");
        updateVO.setDescription("updated desc");
        updateVO.setStatus(0);

        when(permissionMapper.selectById(30L)).thenReturn(permission);
        assertEquals(ResultEnum.SUCCESS.getCode(), permissionController.updatePermission(permissionExternalId, updateVO).getCode());
        verify(permissionMapper).updateById(permission);
        verify(permissionService).evictAllCache(1L);

        when(permissionMapper.selectById(99L)).thenReturn(null);
        String missingPermissionId = IdUtils.toExternalId(99L);
        assertEquals(ResultEnum.RESULT_DATA_NONE.getCode(), permissionController.updatePermission(missingPermissionId, updateVO).getCode());

        when(permissionService.getPermissionCodes("admin", 1L)).thenReturn(Set.of("system:admin"));
        assertEquals(ResultEnum.SUCCESS.getCode(), permissionController.getRolePermissions("admin").getCode());
    }
}
