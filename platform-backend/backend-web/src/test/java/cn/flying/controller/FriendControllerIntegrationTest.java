package cn.flying.controller;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FriendshipMapper;
import cn.flying.dao.mapper.FriendRequestMapper;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.dao.vo.friend.UpdateRemarkVO;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("FriendController Integration Tests")
@TestPropertySource(properties = "test.context=FriendControllerIntegrationTest")
class FriendControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/friends";

    @Autowired
    private AccountMapper accountMapper;

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
        testAccount = createTestAccount(testUserId, testTenantId, "testuser");
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

    private Friendship createFriendship(Long userId1, Long userId2, Long tenantId) {
        Friendship friendship = Friendship.create(userId1, userId2, null);
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

    @Nested
    @DisplayName("Friend Request Operations")
    class FriendRequestTests {

        @Test
        @DisplayName("POST /requests - should send friend request successfully")
        void shouldSendFriendRequestSuccessfully() throws Exception {
            SendFriendRequestVO vo = new SendFriendRequestVO();
            vo.setAddresseeId(IdUtils.toExternalId(friendAccount.getId()));
            vo.setMessage("Hi, let's be friends!");

            performPost(BASE_URL + "/requests", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.requesterId").exists())
                    .andExpect(jsonPath("$.data.addresseeId").exists())
                    .andExpect(jsonPath("$.data.status").value(0));
        }

        @Test
        @DisplayName("GET /requests/received - should return received requests")
        void shouldReturnReceivedRequests() throws Exception {
            createFriendRequest(friendAccount.getId(), testUserId, testTenantId, 0);

            performGet(BASE_URL + "/requests/received?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("GET /requests/sent - should return sent requests")
        void shouldReturnSentRequests() throws Exception {
            createFriendRequest(testUserId, friendAccount.getId(), testTenantId, 0);

            performGet(BASE_URL + "/requests/sent?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("POST /requests/{requestId}/accept - should accept friend request")
        void shouldAcceptFriendRequest() throws Exception {
            FriendRequest request = createFriendRequest(friendAccount.getId(), testUserId, testTenantId, 0);
            String externalId = IdUtils.toExternalId(request.getId());

            performPost(BASE_URL + "/requests/" + externalId + "/accept", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /requests/{requestId}/reject - should reject friend request")
        void shouldRejectFriendRequest() throws Exception {
            FriendRequest request = createFriendRequest(friendAccount.getId(), testUserId, testTenantId, 0);
            String externalId = IdUtils.toExternalId(request.getId());

            performPost(BASE_URL + "/requests/" + externalId + "/reject", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("DELETE /requests/{requestId} - should cancel friend request")
        void shouldCancelFriendRequest() throws Exception {
            FriendRequest request = createFriendRequest(testUserId, friendAccount.getId(), testTenantId, 0);
            String externalId = IdUtils.toExternalId(request.getId());

            performDelete(BASE_URL + "/requests/" + externalId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /requests/pending-count - should return pending count")
        void shouldReturnPendingCount() throws Exception {
            createFriendRequest(friendAccount.getId(), testUserId, testTenantId, 0);

            performGet(BASE_URL + "/requests/pending-count")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.count").isNumber());
        }
    }

    @Nested
    @DisplayName("Friend List Operations")
    class FriendListTests {

        @Test
        @DisplayName("GET / - should return friends list")
        void shouldReturnFriendsList() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);

            performGet(BASE_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }

        @Test
        @DisplayName("GET /all - should return all friends")
        void shouldReturnAllFriends() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);

            performGet(BASE_URL + "/all")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("DELETE /{friendId} - should unfriend successfully")
        void shouldUnfriendSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String externalFriendId = IdUtils.toExternalId(friendAccount.getId());

            performDelete(BASE_URL + "/" + externalFriendId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("PUT /{friendId}/remark - should update remark successfully")
        void shouldUpdateRemarkSuccessfully() throws Exception {
            createFriendship(testUserId, friendAccount.getId(), testTenantId);
            String externalFriendId = IdUtils.toExternalId(friendAccount.getId());

            UpdateRemarkVO vo = new UpdateRemarkVO();
            vo.setRemark("My best friend");

            performPut(BASE_URL + "/" + externalFriendId + "/remark", vo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("User Search Operations")
    class UserSearchTests {

        @Test
        @DisplayName("GET /search - should search users by keyword")
        void shouldSearchUsersByKeyword() throws Exception {
            performGet(BASE_URL + "/search?keyword=friend")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("GET /search - should return 400 for empty keyword")
        void shouldReturn400ForEmptyKeyword() throws Exception {
            performGet(BASE_URL + "/search?keyword=")
                    .andExpect(status().isBadRequest());
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
        @DisplayName("should not see friends from other tenant")
        void shouldNotSeeFriendsFromOtherTenant() throws Exception {
            Account otherTenantUser = createTestAccount(300L, 999L, "othertenant");
            createFriendship(testUserId, otherTenantUser.getId(), 999L);

            MvcResult result = performGet(BASE_URL + "/all")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");

            for (JsonNode friend : data) {
                String friendId = friend.get("id").asText();
                assertThat(friendId).isNotEqualTo(IdUtils.toExternalId(otherTenantUser.getId()));
            }
        }
    }
}
