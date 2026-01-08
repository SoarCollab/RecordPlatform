package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.Message;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.ConversationMapper;
import cn.flying.dao.mapper.MessageMapper;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ConversationController 集成测试
 *
 * 测试私信会话相关接口
 */
@Transactional
@DisplayName("ConversationController Integration Tests")
@TestPropertySource(properties = "test.context=ConversationControllerIntegrationTest")
class ConversationControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/conversations";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;
    private Account otherAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId, "conversationuser");
        otherAccount = createTestAccount(200L, testTenantId, "otheruser");
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

    private Conversation createConversation(Long userA, Long userB, Long tenantId) {
        Long participantA = Math.min(userA, userB);
        Long participantB = Math.max(userA, userB);

        Conversation conversation = new Conversation();
        conversation.setParticipantA(participantA);
        conversation.setParticipantB(participantB);
        conversation.setTenantId(tenantId);
        conversation.setLastMessageAt(new Date());
        conversation.setCreateTime(new Date());
        conversation.setUpdateTime(new Date());
        TenantContext.runWithTenant(tenantId, () -> conversationMapper.insert(conversation));
        return conversation;
    }

    private Message createMessage(Long conversationId, Long senderId, Long receiverId,
                                  Long tenantId, String content, Integer isRead) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setTenantId(tenantId);
        message.setContent(content);
        message.setContentType("text");
        message.setIsRead(isRead);
        message.setCreateTime(new Date());
        message.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> messageMapper.insert(message));
        return message;
    }

    @Nested
    @DisplayName("GET / - Get Conversation List")
    class GetConversationListTests {

        @Test
        @DisplayName("should return conversation list successfully")
        void shouldReturnConversationListSuccessfully() throws Exception {
            createConversation(testUserId, otherAccount.getId(), testTenantId);

            performGet(BASE_URL + "?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("should return empty list when no conversations")
        void shouldReturnEmptyListWhenNoConversations() throws Exception {
            performGet(BASE_URL + "?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray())
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }

        @Test
        @DisplayName("should handle pagination parameters correctly")
        void shouldHandlePaginationParametersCorrectly() throws Exception {
            performGet(BASE_URL + "?pageNum=2&pageSize=5")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.current").value(2))
                    .andExpect(jsonPath("$.data.size").value(5));
        }

        @Test
        @DisplayName("should use default pagination when not specified")
        void shouldUseDefaultPaginationWhenNotSpecified() throws Exception {
            performGet(BASE_URL)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.current").value(1))
                    .andExpect(jsonPath("$.data.size").value(20));
        }
    }

    @Nested
    @DisplayName("GET /{id} - Get Conversation Detail")
    class GetConversationDetailTests {

        @Test
        @DisplayName("should return conversation detail with messages")
        void shouldReturnConversationDetailWithMessages() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);
            createMessage(conversation.getId(), testUserId, otherAccount.getId(),
                    testTenantId, "Hello!", 1);
            createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                    testTenantId, "Hi there!", 0);

            performGet(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId())
                            + "?pageNum=1&pageSize=50")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.conversation").exists())
                    .andExpect(jsonPath("$.data.messages").exists());
        }

        @Test
        @DisplayName("should return empty messages for new conversation")
        void shouldReturnEmptyMessagesForNewConversation() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);

            performGet(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId())
                            + "?pageNum=1&pageSize=50")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.conversation").exists());
        }

        @Test
        @DisplayName("should fail for non-existent conversation")
        void shouldFailForNonExistentConversation() throws Exception {
            String fakeId = IdUtils.toExternalId(999999L);

            performGet(BASE_URL + "/" + fakeId + "?pageNum=1&pageSize=50")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND
        }

        @Test
        @DisplayName("should fail for unauthorized user")
        void shouldFailForUnauthorizedUser() throws Exception {
            // Create conversation between two other users
            Account thirdUser = createTestAccount(300L, testTenantId, "thirduser");
            Conversation conversation = createConversation(otherAccount.getId(), thirdUser.getId(), testTenantId);

            performGet(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId())
                            + "?pageNum=1&pageSize=50")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND (security: hide existence)
        }
    }

    @Nested
    @DisplayName("GET /unread-count - Get Unread Count")
    class GetUnreadCountTests {

        @Test
        @DisplayName("should return correct unread count")
        void shouldReturnCorrectUnreadCount() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);
            // Create unread messages from other user
            createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                    testTenantId, "Message 1", 0);
            createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                    testTenantId, "Message 2", 0);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(2)); // 2 unread messages created
        }

        @Test
        @DisplayName("should return zero for no unread messages")
        void shouldReturnZeroForNoUnreadMessages() throws Exception {
            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        @DisplayName("should not count own sent messages")
        void shouldNotCountOwnSentMessages() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);
            // Create messages sent BY test user (should not count)
            createMessage(conversation.getId(), testUserId, otherAccount.getId(),
                    testTenantId, "My message", 0);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(0));
        }
    }

    @Nested
    @DisplayName("POST /{id}/read - Mark As Read")
    class MarkAsReadTests {

        @Test
        @DisplayName("should mark messages as read successfully")
        void shouldMarkMessagesAsReadSuccessfully() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);
            createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                    testTenantId, "Unread message", 0);

            performPost(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId()) + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("已标记为已读"));
        }

        @Test
        @DisplayName("should be idempotent when marking as read multiple times")
        void shouldBeIdempotentWhenMarkingAsReadMultipleTimes() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);
            createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                    testTenantId, "Message", 0);
            String externalId = IdUtils.toExternalId(conversation.getId());

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
        @DisplayName("should fail for unauthorized conversation")
        void shouldFailForUnauthorizedConversation() throws Exception {
            Account thirdUser = createTestAccount(300L, testTenantId, "thirduser");
            Conversation conversation = createConversation(otherAccount.getId(), thirdUser.getId(), testTenantId);

            performPost(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId()) + "/read", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND (security: hide existence)
        }
    }

    @Nested
    @DisplayName("DELETE /{id} - Delete Conversation")
    class DeleteConversationTests {

        @Test
        @DisplayName("should delete conversation successfully")
        void shouldDeleteConversationSuccessfully() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);

            performDelete(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("删除成功"));
        }

        @Test
        @DisplayName("should fail for non-existent conversation")
        void shouldFailForNonExistentConversation() throws Exception {
            String fakeId = IdUtils.toExternalId(999999L);

            performDelete(BASE_URL + "/" + fakeId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND
        }

        @Test
        @DisplayName("should fail for unauthorized user")
        void shouldFailForUnauthorizedUser() throws Exception {
            Account thirdUser = createTestAccount(300L, testTenantId, "thirduser");
            Conversation conversation = createConversation(otherAccount.getId(), thirdUser.getId(), testTenantId);

            performDelete(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND (security: hide existence)
        }
    }

    @Nested
    @DisplayName("Authentication Tests")
    class AuthenticationTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("should not see conversations from other tenant")
        void shouldNotSeeConversationsFromOtherTenant() throws Exception {
            // Create conversation in different tenant
            Account otherTenantUser = createTestAccount(400L, 999L, "othertenant");
            Account otherTenantUser2 = createTestAccount(500L, 999L, "othertenant2");
            createConversation(otherTenantUser.getId(), otherTenantUser2.getId(), 999L);

            // Test user should see empty list (no conversations in their tenant)
            performGet(BASE_URL + "?pageNum=1&pageSize=20")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }
    }

    @Nested
    @DisplayName("Invalid ID Handling")
    class InvalidIdHandlingTests {

        @Test
        @DisplayName("should handle invalid external ID format")
        void shouldHandleInvalidExternalIdFormat() throws Exception {
            performGet(BASE_URL + "/invalid-id?pageNum=1&pageSize=50")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(60002)); // CONVERSATION_NOT_FOUND (invalid ID parsed as non-existent)
        }

        @Test
        @DisplayName("should handle empty ID")
        void shouldHandleEmptyId() throws Exception {
            mockMvc.perform(withAuth(get(BASE_URL + "//read")))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Message Pagination Tests")
    class MessagePaginationTests {

        @Test
        @DisplayName("should paginate messages correctly")
        void shouldPaginateMessagesCorrectly() throws Exception {
            Conversation conversation = createConversation(testUserId, otherAccount.getId(), testTenantId);

            // Create multiple messages
            for (int i = 0; i < 10; i++) {
                createMessage(conversation.getId(), otherAccount.getId(), testUserId,
                        testTenantId, "Message " + i, 0);
            }

            performGet(BASE_URL + "/" + IdUtils.toExternalId(conversation.getId())
                            + "?pageNum=1&pageSize=5")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.messages").exists())
                    .andExpect(jsonPath("$.data.messages.records.length()").value(5)); // Verify pagination returns correct count
        }
    }
}
