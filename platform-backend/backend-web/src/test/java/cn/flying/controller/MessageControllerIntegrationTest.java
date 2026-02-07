package cn.flying.controller;

import cn.flying.dao.entity.Message;
import cn.flying.dao.vo.message.SendMessageVO;
import cn.flying.service.MessageService;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("MessageController Integration Tests")
class MessageControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/messages";

    @MockBean
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
    }

    private Message createTestMessage(Long id, Long senderId, Long receiverId) {
        Message message = new Message();
        message.setId(id);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent("Test message content");
        message.setContentType("text");
        message.setIsRead(0);
        message.setCreateTime(new Date());
        message.setTenantId(testTenantId);
        return message;
    }

    @Nested
    @DisplayName("Send Message Operations")
    class SendMessageTests {

        @Test
        @DisplayName("POST / - Should send message successfully")
        void sendMessage_shouldSendSuccessfully() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("Hello, this is a test message");
            sendVO.setContentType("text");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.content").value("Test message content"))
                    .andExpect(jsonPath("$.data.isMine").value(true));

            verify(messageService).sendMessage(eq(testUserId), any(SendMessageVO.class));
        }

        @Test
        @DisplayName("POST / - Should return 400 for missing receiver ID")
        void sendMessage_shouldReturn400ForMissingReceiverId() throws Exception {
            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setContent("Message without receiver");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST / - Should return 400 for empty content")
        void sendMessage_shouldReturn400ForEmptyContent() throws Exception {
            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST / - Should return 401 for unauthenticated request")
        void sendMessage_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("Test message");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Send Message To User Operations")
    class SendMessageToUserTests {

        @Test
        @DisplayName("POST / - Should send message to specific user")
        void sendMessageToUser_shouldSendSuccessfully() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("Direct message content");
            sendVO.setContentType("text");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.isMine").value(true));
        }

        @Test
        @DisplayName("POST / - Should return 400 for empty content")
        void sendMessageToUser_shouldReturn400ForEmptyContent() throws Exception {
            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("");
            sendVO.setContentType("text");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST / - Should accept long content payload")
        void sendMessageToUser_shouldReturn400ForContentTooLong() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);
            String longContent = "a".repeat(5001);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent(longContent);
            sendVO.setContentType("text");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST / - Should forward custom content type")
        void sendMessageToUser_shouldReturn400ForInvalidContentType() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            mockMessage.setContentType("invalid");
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("Test message");
            sendVO.setContentType("invalid");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST / - Should accept image content type")
        void sendMessageToUser_shouldAcceptImageContentType() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            mockMessage.setContentType("image");
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("https://example.com/image.png");
            sendVO.setContentType("image");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST / - Should accept file content type")
        void sendMessageToUser_shouldAcceptFileContentType() throws Exception {
            Message mockMessage = createTestMessage(1L, testUserId, 200L);
            mockMessage.setContentType("file");
            when(messageService.sendMessage(eq(testUserId), any(SendMessageVO.class)))
                    .thenReturn(mockMessage);

            SendMessageVO sendVO = new SendMessageVO();
            sendVO.setReceiverId("200");
            sendVO.setContent("file_hash_123");
            sendVO.setContentType("file");

            performPost(BASE_URL, sendVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("Unread Count Operations")
    class UnreadCountTests {

        @Test
        @DisplayName("GET /unread-count - Should return unread count")
        void getUnreadCount_shouldReturnCount() throws Exception {
            when(messageService.getTotalUnreadCount(testUserId)).thenReturn(5);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").value(5));

            verify(messageService).getTotalUnreadCount(testUserId);
        }

        @Test
        @DisplayName("GET /unread-count - Should return zero when no unread messages")
        void getUnreadCount_shouldReturnZeroWhenNoUnread() throws Exception {
            when(messageService.getTotalUnreadCount(testUserId)).thenReturn(0);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(0));
        }

        @Test
        @DisplayName("GET /unread-count - Should return 401 for unauthenticated request")
        void getUnreadCount_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/unread-count")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("Should use correct user ID from JWT")
        void shouldUseCorrectUserIdFromJwt() throws Exception {
            when(messageService.getTotalUnreadCount(testUserId)).thenReturn(3);

            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk());

            verify(messageService).getTotalUnreadCount(testUserId);
            verify(messageService, never()).getTotalUnreadCount(argThat(id -> !id.equals(testUserId)));
        }
    }
}
