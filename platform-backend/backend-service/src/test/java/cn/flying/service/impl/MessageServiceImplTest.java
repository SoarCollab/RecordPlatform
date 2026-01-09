package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.Message;
import cn.flying.dao.mapper.MessageMapper;
import cn.flying.dao.vo.message.SendMessageVO;
import cn.flying.service.AccountService;
import cn.flying.service.ConversationService;
import cn.flying.service.FriendService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("MessageServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private ConversationService conversationService;

    @Mock
    private AccountService accountService;

    @Mock
    private FriendService friendService;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Spy
    @InjectMocks
    private MessageServiceImpl messageService;

    private static final Long SENDER_ID = 1001L;
    private static final Long RECEIVER_ID = 1002L;
    private static final Long TENANT_ID = 1L;
    private static final Long CONVERSATION_ID = 5001L;

    private MockedStatic<TenantContext> tenantContextMock;
    private MockedStatic<IdUtils> idUtilsMock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(messageService, "baseMapper", messageMapper);
        
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
    @DisplayName("sendMessage")
    class SendMessage {

        @Test
        @DisplayName("should send message successfully")
        void sendMessage_success() {
            SendMessageVO vo = createSendMessageVO();
            Account receiver = createAccount(RECEIVER_ID, "receiver");
            Account sender = createAccount(SENDER_ID, "sender");
            Conversation conversation = createConversation();
            
            when(accountService.findAccountById(RECEIVER_ID)).thenReturn(receiver);
            when(accountService.findAccountById(SENDER_ID)).thenReturn(sender);
            when(friendService.areFriends(SENDER_ID, RECEIVER_ID)).thenReturn(true);
            when(conversationService.getOrCreateConversation(SENDER_ID, RECEIVER_ID)).thenReturn(conversation);
            when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
                Message msg = inv.getArgument(0);
                msg.setId(9001L);
                return 1;
            });

            Message result = messageService.sendMessage(SENDER_ID, vo);

            assertNotNull(result);
            assertEquals(SENDER_ID, result.getSenderId());
            assertEquals(RECEIVER_ID, result.getReceiverId());
            assertEquals("Hello!", result.getContent());
            verify(conversationService).updateLastMessage(CONVERSATION_ID, 9001L);
            verify(sseEmitterManager).sendToUser(eq(TENANT_ID), eq(RECEIVER_ID), any(SseEvent.class));
        }

        @Test
        @DisplayName("should throw when sender equals receiver")
        void sendMessage_toSelf_throws() {
            SendMessageVO vo = new SendMessageVO();
            vo.setReceiverId("ext_" + SENDER_ID);
            vo.setContent("Hello!");

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> messageService.sendMessage(SENDER_ID, vo));

            assertEquals(ResultEnum.CANNOT_MESSAGE_SELF, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when receiver does not exist")
        void sendMessage_receiverNotExist_throws() {
            SendMessageVO vo = createSendMessageVO();
            when(accountService.findAccountById(RECEIVER_ID)).thenReturn(null);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> messageService.sendMessage(SENDER_ID, vo));

            assertEquals(ResultEnum.USER_NOT_EXIST, ex.getResultEnum());
        }

        @Test
        @DisplayName("should throw when not friends")
        void sendMessage_notFriends_throws() {
            SendMessageVO vo = createSendMessageVO();
            Account receiver = createAccount(RECEIVER_ID, "receiver");
            
            when(accountService.findAccountById(RECEIVER_ID)).thenReturn(receiver);
            when(friendService.areFriends(SENDER_ID, RECEIVER_ID)).thenReturn(false);

            GeneralException ex = assertThrows(GeneralException.class,
                    () -> messageService.sendMessage(SENDER_ID, vo));

            assertEquals(ResultEnum.NOT_FRIENDS, ex.getResultEnum());
        }

        @Test
        @DisplayName("should use default content type when not specified")
        void sendMessage_defaultContentType() {
            SendMessageVO vo = createSendMessageVO();
            vo.setContentType(null);
            Account receiver = createAccount(RECEIVER_ID, "receiver");
            Account sender = createAccount(SENDER_ID, "sender");
            Conversation conversation = createConversation();

            when(accountService.findAccountById(RECEIVER_ID)).thenReturn(receiver);
            when(accountService.findAccountById(SENDER_ID)).thenReturn(sender);
            when(friendService.areFriends(SENDER_ID, RECEIVER_ID)).thenReturn(true);
            when(conversationService.getOrCreateConversation(SENDER_ID, RECEIVER_ID)).thenReturn(conversation);

            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            doAnswer(inv -> {
                Message msg = inv.getArgument(0);
                msg.setId(9001L);
                return true;  // IRepository.save() returns boolean
            }).when(messageService).save(messageCaptor.capture());

            Message result = messageService.sendMessage(SENDER_ID, vo);

            assertNotNull(result);
            assertEquals("text", messageCaptor.getValue().getContentType());
        }
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("should mark messages as read")
        void markAsRead_success() {
            when(conversationService.getById(CONVERSATION_ID)).thenReturn(createConversation());
            when(messageMapper.markConversationAsRead(eq(CONVERSATION_ID), eq(SENDER_ID), any(), eq(TENANT_ID)))
                    .thenReturn(5);

            assertDoesNotThrow(() -> messageService.markAsRead(SENDER_ID, CONVERSATION_ID));

            verify(messageMapper).markConversationAsRead(eq(CONVERSATION_ID), eq(SENDER_ID), any(), eq(TENANT_ID));
        }

        @Test
        @DisplayName("should handle no messages to mark")
        void markAsRead_noMessages() {
            when(conversationService.getById(CONVERSATION_ID)).thenReturn(createConversation());
            when(messageMapper.markConversationAsRead(eq(CONVERSATION_ID), eq(SENDER_ID), any(), eq(TENANT_ID)))
                    .thenReturn(0);

            assertDoesNotThrow(() -> messageService.markAsRead(SENDER_ID, CONVERSATION_ID));
        }
    }

    @Nested
    @DisplayName("getTotalUnreadCount")
    class GetTotalUnreadCount {

        @Test
        @DisplayName("should return total unread count")
        void getTotalUnreadCount_success() {
            when(messageMapper.countUnreadMessages(SENDER_ID, TENANT_ID)).thenReturn(10);

            int count = messageService.getTotalUnreadCount(SENDER_ID);

            assertEquals(10, count);
        }

        @Test
        @DisplayName("should return zero when no unread messages")
        void getTotalUnreadCount_zero() {
            when(messageMapper.countUnreadMessages(SENDER_ID, TENANT_ID)).thenReturn(0);

            int count = messageService.getTotalUnreadCount(SENDER_ID);

            assertEquals(0, count);
        }
    }

    @Nested
    @DisplayName("getUnreadCountInConversation")
    class GetUnreadCountInConversation {

        @Test
        @DisplayName("should return unread count in conversation")
        void getUnreadCountInConversation_success() {
            when(messageMapper.countUnreadInConversation(CONVERSATION_ID, SENDER_ID, TENANT_ID)).thenReturn(3);

            int count = messageService.getUnreadCountInConversation(CONVERSATION_ID, SENDER_ID);

            assertEquals(3, count);
        }

        @Test
        @DisplayName("should return zero when no unread in conversation")
        void getUnreadCountInConversation_zero() {
            when(messageMapper.countUnreadInConversation(CONVERSATION_ID, SENDER_ID, TENANT_ID)).thenReturn(0);

            int count = messageService.getUnreadCountInConversation(CONVERSATION_ID, SENDER_ID);

            assertEquals(0, count);
        }
    }

    private SendMessageVO createSendMessageVO() {
        SendMessageVO vo = new SendMessageVO();
        vo.setReceiverId("ext_" + RECEIVER_ID);
        vo.setContent("Hello!");
        vo.setContentType("text");
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

    private Conversation createConversation() {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setParticipantA(SENDER_ID);
        conversation.setParticipantB(RECEIVER_ID);
        return conversation;
    }
}
