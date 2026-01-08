package cn.flying.controller;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TicketMapper;
import cn.flying.dao.vo.ticket.TicketCreateVO;
import cn.flying.dao.vo.ticket.TicketReplyVO;
import cn.flying.dao.vo.ticket.TicketUpdateVO;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.test.support.BaseControllerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("TicketController Integration Tests")
@TestPropertySource(properties = "test.context=TicketControllerIntegrationTest")
class TicketControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/tickets";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;
    private Ticket testTicket;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId);
        testTicket = createTestTicket(testUserId, testTenantId);
    }

    private Account createTestAccount(Long userId, Long tenantId) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername("testuser_" + userId);
        account.setEmail("testuser_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    private Ticket createTestTicket(Long userId, Long tenantId) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("TKT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        ticket.setTitle("Test Ticket");
        ticket.setContent("This is a test ticket content");
        ticket.setPriority(1);
        ticket.setCategory(2);
        ticket.setStatus(0);
        ticket.setCreatorId(userId);
        ticket.setCreateTime(new Date());
        ticket.setUpdateTime(new Date());
        ticket.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> ticketMapper.insert(ticket));
        return ticket;
    }

    @Nested
    @DisplayName("User Ticket Operations")
    class UserTicketTests {

        @Test
        @DisplayName("GET / - should return user's ticket list")
        void shouldReturnUserTicketList() throws Exception {
            performGet(BASE_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("GET /{id} - should return ticket detail")
        void shouldReturnTicketDetail() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());

            performGet(BASE_URL + "/" + externalId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value(testTicket.getTitle()));
        }

        @Test
        @DisplayName("POST / - should create ticket successfully")
        void shouldCreateTicketSuccessfully() throws Exception {
            TicketCreateVO vo = new TicketCreateVO();
            vo.setTitle("New Test Ticket");
            vo.setContent("New ticket content for testing");
            vo.setPriority(2);
            vo.setCategory(1);

            performPost(BASE_URL, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("New Test Ticket"));
        }

        @Test
        @DisplayName("PUT /{id} - should update ticket successfully")
        void shouldUpdateTicketSuccessfully() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());

            TicketUpdateVO vo = new TicketUpdateVO();
            vo.setTitle("Updated Ticket Title");
            vo.setContent("Updated content");

            performPut(BASE_URL + "/" + externalId, vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("Updated Ticket Title"));
        }

        @Test
        @DisplayName("POST /{id}/reply - should reply to ticket")
        void shouldReplyToTicket() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());

            TicketReplyVO vo = new TicketReplyVO();
            vo.setContent("This is a reply to the ticket");

            performPost(BASE_URL + "/" + externalId + "/reply", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /{id}/close - should close ticket")
        void shouldCloseTicket() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());

            performPost(BASE_URL + "/" + externalId + "/close", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /pending-count - should return pending count")
        void shouldReturnPendingCount() throws Exception {
            performGet(BASE_URL + "/pending-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").isNumber());
        }
    }

    @Nested
    @DisplayName("Admin Ticket Operations")
    class AdminTicketTests {

        @Test
        @DisplayName("GET /admin/list - should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/admin/list?pageNum=1&pageSize=10")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /admin/list - should return all tickets for admin")
        void shouldReturnAllTicketsForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet(BASE_URL + "/admin/list?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("PUT /admin/{id}/assign - should require admin role")
        void assignShouldRequireAdminRole() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());
            String assigneeId = IdUtils.toExternalId(testUserId);

            performPut(BASE_URL + "/admin/" + externalId + "/assign?assigneeId=" + assigneeId, null)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /admin/{id}/status - should require admin role")
        void updateStatusShouldRequireAdminRole() throws Exception {
            String externalId = IdUtils.toExternalId(testTicket.getId());

            performPut(BASE_URL + "/admin/" + externalId + "/status?status=1", null)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /admin/pending-count - should require admin role")
        void adminPendingCountShouldRequireAdminRole() throws Exception {
            performGet(BASE_URL + "/admin/pending-count")
                    .andExpect(status().isForbidden());
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
        @DisplayName("should not see tickets from other tenant")
        void shouldNotSeeTicketsFromOtherTenant() throws Exception {
            Ticket otherTenantTicket = createTestTicket(testUserId, 999L);

            MvcResult result = performGet(BASE_URL + "?pageNum=1&pageSize=100")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode records = objectMapper.readTree(content).get("data").get("records");

            for (JsonNode ticket : records) {
                String ticketNo = ticket.get("ticketNo").asText();
                assertThat(ticketNo).isNotEqualTo(otherTenantTicket.getTicketNo());
            }
        }
    }
}
