package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.Message;
import cn.flying.dao.mapper.ConversationMapper;
import cn.flying.dao.mapper.MessageMapper;
import cn.flying.dao.vo.message.ConversationDetailVO;
import cn.flying.dao.vo.message.ConversationVO;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.service.AccountService;
import cn.flying.service.MessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationMapper conversationMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private AccountService accountService;

    @Mock
    private MessageService messageService;

    @Spy
    @InjectMocks
    private ConversationServiceImpl conversationService;

    private static final Long USER_A = 1001L;
    private static final Long USER_B = 2001L;
    private static final Long CONVERSATION_ID = 5001L;
    private static final Long TENANT_ID = 1L;

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(conversationService, "baseMapper", conversationMapper);

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
    @DisplayName("getOrCreateConversation")
    class GetOrCreateConversation {

        @Test
        @DisplayName("should return existing conversation")
        void getOrCreate_existing() {
            Conversation existing = createConversation(USER_A, USER_B);
            when(conversationMapper.selectByParticipants(USER_A, USER_B, TENANT_ID)).thenReturn(existing);

            Conversation result = conversationService.getOrCreateConversation(USER_A, USER_B);

            assertNotNull(result);
            assertEquals(CONVERSATION_ID, result.getId());
            verify(conversationService, never()).save(any());
        }

        @Test
        @DisplayName("should create new conversation when not exists")
        void getOrCreate_create() {
            when(conversationMapper.selectByParticipants(USER_A, USER_B, TENANT_ID)).thenReturn(null);
            doAnswer(inv -> {
                Conversation conv = inv.getArgument(0);
                conv.setId(CONVERSATION_ID);
                return true;
            }).when(conversationService).save(any(Conversation.class));

            Conversation result = conversationService.getOrCreateConversation(USER_A, USER_B);

            assertNotNull(result);
            assertEquals(USER_A, result.getParticipantA());
            assertEquals(USER_B, result.getParticipantB());
            verify(conversationService).save(any(Conversation.class));
        }

        @Test
        @DisplayName("should normalize participant order - smaller ID first")
        void getOrCreate_normalizeOrder() {
            when(conversationMapper.selectByParticipants(USER_A, USER_B, TENANT_ID)).thenReturn(null);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            doAnswer(inv -> {
                Conversation conv = inv.getArgument(0);
                conv.setId(CONVERSATION_ID);
                return true;
            }).when(conversationService).save(captor.capture());

            conversationService.getOrCreateConversation(USER_B, USER_A);

            Conversation saved = captor.getValue();
            assertEquals(USER_A, saved.getParticipantA());
            assertEquals(USER_B, saved.getParticipantB());
        }
    }

    @Nested
    @DisplayName("getConversationList")
    class GetConversationList {

        @Test
        @DisplayName("should return conversation list for user")
        @SuppressWarnings("unchecked")
        void getConversationList_success() {
            Conversation conv1 = createConversation(USER_A, USER_B);
            conv1.setLastMessageId(9001L);
            conv1.setLastMessageAt(new Date());

            Account otherUser = createAccount(USER_B, "userB");
            Message lastMessage = createMessage(9001L, "Hello");

            Page<Conversation> page = new Page<>(1, 10);
            page.setRecords(List.of(conv1));
            page.setTotal(1);

            doReturn(page).when(conversationService).page(any(Page.class), any(LambdaQueryWrapper.class));
            when(accountService.findAccountById(USER_B)).thenReturn(otherUser);
            when(messageMapper.selectById(9001L)).thenReturn(lastMessage);
            when(messageService.getUnreadCountInConversation(CONVERSATION_ID, USER_A)).thenReturn(2);

            IPage<ConversationVO> result = conversationService.getConversationList(USER_A, new Page<>(1, 10));

            assertEquals(1, result.getRecords().size());
            ConversationVO vo = result.getRecords().get(0);
            assertEquals("ext_" + USER_B, vo.getOtherUserId());
            assertEquals("userB", vo.getOtherUsername());
            assertEquals("Hello", vo.getLastMessageContent());
            assertEquals(2, vo.getUnreadCount());
        }

        @Test
        @DisplayName("should handle missing other user")
        @SuppressWarnings("unchecked")
        void getConversationList_missingOtherUser() {
            Conversation conv = createConversation(USER_A, USER_B);

            Page<Conversation> page = new Page<>(1, 10);
            page.setRecords(List.of(conv));

            doReturn(page).when(conversationService).page(any(Page.class), any(LambdaQueryWrapper.class));
            when(accountService.findAccountById(USER_B)).thenReturn(null);
            when(messageService.getUnreadCountInConversation(CONVERSATION_ID, USER_A)).thenReturn(0);

            IPage<ConversationVO> result = conversationService.getConversationList(USER_A, new Page<>(1, 10));

            assertNull(result.getRecords().get(0).getOtherUsername());
        }

        @Test
        @DisplayName("should handle no last message")
        @SuppressWarnings("unchecked")
        void getConversationList_noLastMessage() {
            Conversation conv = createConversation(USER_A, USER_B);
            conv.setLastMessageId(null);

            Page<Conversation> page = new Page<>(1, 10);
            page.setRecords(List.of(conv));

            doReturn(page).when(conversationService).page(any(Page.class), any(LambdaQueryWrapper.class));
            lenient().when(accountService.findAccountById(USER_B)).thenReturn(null);
            when(messageService.getUnreadCountInConversation(CONVERSATION_ID, USER_A)).thenReturn(0);

            IPage<ConversationVO> result = conversationService.getConversationList(USER_A, new Page<>(1, 10));

            assertNull(result.getRecords().get(0).getLastMessageContent());
            verify(messageMapper, never()).selectById(any());
        }
    }

    @Nested
    @DisplayName("getConversationDetail")
    class GetConversationDetail {

        @Test
        @DisplayName("should return conversation detail successfully")
        void getConversationDetail_success() {
            Conversation conv = createConversation(USER_A, USER_B);
            Account otherUser = createAccount(USER_B, "userB");

            Page<MessageVO> messagePage = new Page<>(1, 20);
            messagePage.setRecords(List.of(createMessageVO()));
            messagePage.setTotal(1);
            messagePage.setPages(1);

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            when(accountService.findAccountById(USER_B)).thenReturn(otherUser);
            when(messageService.getMessages(eq(USER_A), eq(CONVERSATION_ID), any(Page.class))).thenReturn(messagePage);

            ConversationDetailVO result = conversationService.getConversationDetail(USER_A, CONVERSATION_ID, 1, 20);

            assertNotNull(result);
            assertNotNull(result.getConversation());
            assertEquals("ext_" + CONVERSATION_ID, result.getConversation().getId());
            assertEquals("ext_" + USER_B, result.getConversation().getOtherUserId());
            assertEquals("userB", result.getConversation().getOtherUsername());
            assertNotNull(result.getMessages());
            assertEquals(1, result.getMessages().getRecords().size());
        }

        @Test
        @DisplayName("should throw when conversation not found")
        void getConversationDetail_notFound() {
            when(conversationService.getById(CONVERSATION_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> conversationService.getConversationDetail(USER_A, CONVERSATION_ID, 1, 20));

            assertEquals(ResultEnum.CONVERSATION_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when user is not participant")
        void getConversationDetail_notParticipant() {
            Conversation conv = createConversation(USER_A, USER_B);
            Long otherUserId = 9999L;

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> conversationService.getConversationDetail(otherUserId, CONVERSATION_ID, 1, 20));

            assertEquals(ResultEnum.CONVERSATION_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should calculate hasMore correctly")
        void getConversationDetail_hasMore() {
            Conversation conv = createConversation(USER_A, USER_B);

            Page<MessageVO> messagePage = new Page<>(1, 20);
            messagePage.setRecords(List.of(createMessageVO()));
            messagePage.setTotal(50);
            messagePage.setCurrent(1);
            messagePage.setPages(3);

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            lenient().when(accountService.findAccountById(USER_B)).thenReturn(null);
            when(messageService.getMessages(eq(USER_A), eq(CONVERSATION_ID), any(Page.class))).thenReturn(messagePage);

            ConversationDetailVO result = conversationService.getConversationDetail(USER_A, CONVERSATION_ID, 1, 20);

            assertTrue(result.getHasMore());
            assertEquals(50L, result.getTotalMessages());
        }

        @Test
        @DisplayName("should set hasMore to false on last page")
        void getConversationDetail_lastPage() {
            Conversation conv = createConversation(USER_A, USER_B);

            Page<MessageVO> messagePage = new Page<>(3, 20);
            messagePage.setRecords(List.of(createMessageVO()));
            messagePage.setTotal(50);
            messagePage.setCurrent(3);
            messagePage.setPages(3);

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            lenient().when(accountService.findAccountById(USER_B)).thenReturn(null);
            when(messageService.getMessages(eq(USER_A), eq(CONVERSATION_ID), any(Page.class))).thenReturn(messagePage);

            ConversationDetailVO result = conversationService.getConversationDetail(USER_A, CONVERSATION_ID, 3, 20);

            assertFalse(result.getHasMore());
        }
    }

    @Nested
    @DisplayName("getUnreadConversationCount")
    class GetUnreadConversationCount {

        @Test
        @DisplayName("should return unread conversation count")
        void getUnreadConversationCount_success() {
            when(conversationMapper.countUnreadConversations(USER_A, TENANT_ID)).thenReturn(3);

            int count = conversationService.getUnreadConversationCount(USER_A);

            assertEquals(3, count);
        }

        @Test
        @DisplayName("should return zero when no unread conversations")
        void getUnreadConversationCount_zero() {
            when(conversationMapper.countUnreadConversations(USER_A, TENANT_ID)).thenReturn(0);

            int count = conversationService.getUnreadConversationCount(USER_A);

            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("deleteConversation")
    class DeleteConversation {

        @Test
        @DisplayName("should delete conversation successfully")
        void deleteConversation_success() {
            Conversation conv = createConversation(USER_A, USER_B);

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            doReturn(true).when(conversationService).removeById(CONVERSATION_ID);

            assertDoesNotThrow(() -> conversationService.deleteConversation(USER_A, CONVERSATION_ID));

            verify(conversationService).removeById(CONVERSATION_ID);
        }

        @Test
        @DisplayName("should throw when conversation not found")
        void deleteConversation_notFound() {
            when(conversationService.getById(CONVERSATION_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> conversationService.deleteConversation(USER_A, CONVERSATION_ID));
            assertEquals(ResultEnum.CONVERSATION_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when user is not participant")
        void deleteConversation_notParticipant() {
            Conversation conv = createConversation(USER_A, USER_B);
            Long otherUserId = 9999L;

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> conversationService.deleteConversation(otherUserId, CONVERSATION_ID));

            assertEquals(ResultEnum.CONVERSATION_NOT_FOUND, ex.getResultEnum());
        }

        @Test
        @DisplayName("should allow participant B to delete")
        void deleteConversation_participantB() {
            Conversation conv = createConversation(USER_A, USER_B);

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            doReturn(true).when(conversationService).removeById(CONVERSATION_ID);

            assertDoesNotThrow(() -> conversationService.deleteConversation(USER_B, CONVERSATION_ID));

            verify(conversationService).removeById(CONVERSATION_ID);
        }
    }

    @Nested
    @DisplayName("updateLastMessage")
    class UpdateLastMessage {

        @Test
        @DisplayName("should update last message")
        void updateLastMessage_success() {
            Conversation conv = createConversation(USER_A, USER_B);
            Long messageId = 9001L;

            when(conversationService.getById(CONVERSATION_ID)).thenReturn(conv);
            doReturn(true).when(conversationService).updateById(any(Conversation.class));

            assertDoesNotThrow(() -> conversationService.updateLastMessage(CONVERSATION_ID, messageId));

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationService).updateById(captor.capture());
            assertEquals(messageId, captor.getValue().getLastMessageId());
            assertNotNull(captor.getValue().getLastMessageAt());
        }

        @Test
        @DisplayName("should do nothing when conversation not found")
        void updateLastMessage_notFound() {
            when(conversationService.getById(CONVERSATION_ID)).thenReturn(null);

            assertDoesNotThrow(() -> conversationService.updateLastMessage(CONVERSATION_ID, 9001L));

            verify(conversationService, never()).updateById(any());
        }
    }

    private Conversation createConversation(Long userA, Long userB) {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setParticipantA(Math.min(userA, userB));
        conversation.setParticipantB(Math.max(userA, userB));
        conversation.setTenantId(TENANT_ID);
        conversation.setCreateTime(new Date());
        return conversation;
    }

    private Account createAccount(Long id, String username) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setAvatar("/avatars/default.png");
        account.setTenantId(TENANT_ID);
        return account;
    }

    private Message createMessage(Long id, String content) {
        Message message = new Message();
        message.setId(id);
        message.setContent(content);
        message.setContentType("text");
        message.setSenderId(USER_A);
        message.setReceiverId(USER_B);
        return message;
    }

    private MessageVO createMessageVO() {
        MessageVO vo = new MessageVO();
        vo.setId("ext_9001");
        vo.setContent("Hello");
        vo.setContentType("text");
        return vo;
    }
}
