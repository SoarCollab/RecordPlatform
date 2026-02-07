package cn.flying.controller;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.AnnouncementMapper;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("AnnouncementController Integration Tests")
@TestPropertySource(properties = "test.context=AnnouncementControllerIntegrationTest")
class AnnouncementControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/announcements";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private AnnouncementMapper announcementMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;
    private Announcement testAnnouncement;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId);
        testAnnouncement = createTestAnnouncement(testUserId, testTenantId);
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

    private Announcement createTestAnnouncement(Long publisherId, Long tenantId) {
        Announcement announcement = new Announcement();
        announcement.setTenantId(tenantId);
        announcement.setTitle("Test Announcement");
        announcement.setContent("This is a test announcement content");
        announcement.setPriority(1);
        announcement.setIsPinned(0);
        announcement.setPublishTime(new Date());
        announcement.setExpireTime(new Date(System.currentTimeMillis() + 86400000L));
        announcement.setStatus(1);
        announcement.setPublisherId(publisherId);
        announcement.setCreateTime(new Date());
        announcement.setUpdateTime(new Date());
        announcement.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> announcementMapper.insert(announcement));
        return announcement;
    }

    @Nested
    @DisplayName("User Announcement Operations")
    class UserAnnouncementTests {

        @Test
        @DisplayName("GET /latest - should return latest announcements")
        void shouldReturnLatestAnnouncements() throws Exception {
            performGet(BASE_URL + "/latest?limit=5")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("GET / - should return announcement list")
        void shouldReturnAnnouncementList() throws Exception {
            performGet(BASE_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("GET /{id} - should return announcement detail")
        void shouldReturnAnnouncementDetail() throws Exception {
            String externalId = IdUtils.toExternalId(testAnnouncement.getId());

            performGet(BASE_URL + "/" + externalId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value(testAnnouncement.getTitle()));
        }

        @Test
        @DisplayName("GET /unread-count - should return unread count")
        void shouldReturnUnreadCount() throws Exception {
            performGet(BASE_URL + "/unread-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").isNumber());
        }

        @Test
        @DisplayName("POST /{id}/read - should mark as read")
        void shouldMarkAsRead() throws Exception {
            String externalId = IdUtils.toExternalId(testAnnouncement.getId());

            performPut(BASE_URL + "/" + externalId + "/read-status", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /read-all - should mark all as read")
        void shouldMarkAllAsRead() throws Exception {
            performPut(BASE_URL + "/read-status", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("Admin Announcement Operations")
    class AdminAnnouncementTests {

        @Test
        @DisplayName("GET /admin/list - should require admin role")
        void shouldRequireAdminRole() throws Exception {
            performGet("/api/v1/admin/announcements?pageNum=1&pageSize=10")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /admin/list - should return list for admin")
        void shouldReturnListForAdmin() throws Exception {
            setTestAdmin(100L, 1L);

            performGet("/api/v1/admin/announcements?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("POST / - should require admin role")
        void publishShouldRequireAdminRole() throws Exception {
            AnnouncementCreateVO vo = new AnnouncementCreateVO();
            vo.setTitle("New Announcement");
            vo.setContent("Content");
            vo.setPriority(0);

            performPost(BASE_URL, vo)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /{id} - should require admin role")
        void updateShouldRequireAdminRole() throws Exception {
            String externalId = IdUtils.toExternalId(testAnnouncement.getId());

            AnnouncementCreateVO vo = new AnnouncementCreateVO();
            vo.setTitle("Updated Title");
            vo.setContent("Updated Content");

            performPut(BASE_URL + "/" + externalId, vo)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("DELETE /{id} - should require admin role")
        void deleteShouldRequireAdminRole() throws Exception {
            String externalId = IdUtils.toExternalId(testAnnouncement.getId());

            performDelete(BASE_URL + "/" + externalId)
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
}
