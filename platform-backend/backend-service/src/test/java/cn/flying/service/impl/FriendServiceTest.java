package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.mapper.FriendRequestMapper;
import cn.flying.dao.mapper.FriendshipMapper;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.service.AccountService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.test.builders.AccountTestBuilder;
import cn.flying.test.builders.FriendRequestTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FriendServiceImpl.
 * Verifies friend request lifecycle, friendship management, and SSE notifications.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FriendService Tests")
class FriendServiceTest {

    @Mock
    private FriendshipMapper friendshipMapper;

    @Mock
    private FriendRequestMapper friendRequestMapper;

    @Mock
    private AccountService accountService;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @InjectMocks
    private FriendServiceImpl friendService;

    private static MockedStatic<TenantContext> tenantContextMock;
    private static MockedStatic<IdUtils> idUtilsMock;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_A_ID = 100L;
    private static final Long USER_B_ID = 200L;

    @BeforeAll
    static void setUpClass() {
        tenantContextMock = mockStatic(TenantContext.class);
        tenantContextMock.when(TenantContext::getTenantId).thenReturn(TENANT_ID);

        idUtilsMock = mockStatic(IdUtils.class);
        // Mock ID encoding/decoding - external IDs are prefixed with "EXT_"
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenAnswer(invocation -> {
            String externalId = invocation.getArgument(0);
            if (externalId != null && externalId.startsWith("EXT_")) {
                return Long.parseLong(externalId.substring(4));
            }
            return null;
        });
        idUtilsMock.when(() -> IdUtils.toExternalId(anyLong())).thenAnswer(invocation -> {
            Long internalId = invocation.getArgument(0);
            return "EXT_" + internalId;
        });
    }

    @AfterAll
    static void tearDownClass() {
        tenantContextMock.close();
        idUtilsMock.close();
    }

    @BeforeEach
    void setUp() {
        AccountTestBuilder.resetIdCounter();
        FriendRequestTestBuilder.resetIdCounter();
        // ServiceImpl uses baseMapper internally, need to inject the mock
        ReflectionTestUtils.setField(friendService, "baseMapper", friendshipMapper);
    }

    @Nested
    @DisplayName("Send Friend Request")
    class SendRequest {

        @Test
        @DisplayName("should create pending request with valid parameters")
        void shouldCreatePendingRequest() {
            // Given
            Account addressee = AccountTestBuilder.anAccountWithId(USER_B_ID);
            Account requester = AccountTestBuilder.anAccountWithId(USER_A_ID);
            SendFriendRequestVO vo = createSendRequestVO(USER_B_ID, "Hello!");

            when(accountService.findAccountById(USER_B_ID)).thenReturn(addressee);
            when(accountService.findAccountById(USER_A_ID)).thenReturn(requester);
            when(friendshipMapper.areFriends(anyLong(), anyLong(), anyLong())).thenReturn(0);
            when(friendRequestMapper.countPendingBetween(anyLong(), anyLong(), anyLong())).thenReturn(0);
            // Simulate ID generation by mapper
            doAnswer(invocation -> {
                FriendRequest r = invocation.getArgument(0);
                r.setId(1L);
                return 1;
            }).when(friendRequestMapper).insert(any(FriendRequest.class));

            // When
            FriendRequest result = friendService.sendRequest(USER_A_ID, vo);

            // Then
            assertNotNull(result);
            assertEquals(USER_A_ID, result.getRequesterId());
            assertEquals(USER_B_ID, result.getAddresseeId());
            assertEquals("Hello!", result.getMessage());
            assertEquals(FriendRequest.STATUS_PENDING, result.getStatus());

            verify(friendRequestMapper).insert(any(FriendRequest.class));
        }

        @Test
        @DisplayName("should send SSE notification to addressee")
        void shouldSendSseNotification() {
            // Given
            Account addressee = AccountTestBuilder.anAccountWithId(USER_B_ID);
            Account requester = AccountTestBuilder.anAccountWithId(USER_A_ID);
            SendFriendRequestVO vo = createSendRequestVO(USER_B_ID, "Hi!");

            when(accountService.findAccountById(USER_B_ID)).thenReturn(addressee);
            when(accountService.findAccountById(USER_A_ID)).thenReturn(requester);
            when(friendshipMapper.areFriends(anyLong(), anyLong(), anyLong())).thenReturn(0);
            when(friendRequestMapper.countPendingBetween(anyLong(), anyLong(), anyLong())).thenReturn(0);
            // Simulate ID generation by mapper
            doAnswer(invocation -> {
                FriendRequest r = invocation.getArgument(0);
                r.setId(1L);
                return 1;
            }).when(friendRequestMapper).insert(any(FriendRequest.class));

            // When
            friendService.sendRequest(USER_A_ID, vo);

            // Then
            verify(sseEmitterManager).sendToUser(eq(TENANT_ID), eq(USER_B_ID), any(SseEvent.class));
        }

        @Test
        @DisplayName("should reject self-request")
        void shouldRejectSelfRequest() {
            // Given
            SendFriendRequestVO vo = createSendRequestVO(USER_A_ID, "Hi!");

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.sendRequest(USER_A_ID, vo));

            assertEquals(ResultEnum.CANNOT_ADD_SELF.getCode(), ex.getResultEnum().getCode());
            verify(friendRequestMapper, never()).insert(any(FriendRequest.class));
        }

        @Test
        @DisplayName("should reject if target user not found")
        void shouldRejectIfUserNotFound() {
            // Given
            SendFriendRequestVO vo = createSendRequestVO(USER_B_ID, "Hi!");

            when(accountService.findAccountById(USER_B_ID)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.sendRequest(USER_A_ID, vo));

            assertEquals(ResultEnum.USER_NOT_EXIST.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject if already friends")
        void shouldRejectIfAlreadyFriends() {
            // Given
            Account addressee = AccountTestBuilder.anAccountWithId(USER_B_ID);
            SendFriendRequestVO vo = createSendRequestVO(USER_B_ID, "Hi!");

            when(accountService.findAccountById(USER_B_ID)).thenReturn(addressee);
            when(friendshipMapper.areFriends(anyLong(), anyLong(), anyLong())).thenReturn(1);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.sendRequest(USER_A_ID, vo));

            assertEquals(ResultEnum.ALREADY_FRIENDS.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject if pending request exists")
        void shouldRejectIfPendingExists() {
            // Given
            Account addressee = AccountTestBuilder.anAccountWithId(USER_B_ID);
            SendFriendRequestVO vo = createSendRequestVO(USER_B_ID, "Hi!");

            when(accountService.findAccountById(USER_B_ID)).thenReturn(addressee);
            when(friendshipMapper.areFriends(anyLong(), anyLong(), anyLong())).thenReturn(0);
            when(friendRequestMapper.countPendingBetween(anyLong(), anyLong(), anyLong())).thenReturn(1);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.sendRequest(USER_A_ID, vo));

            assertEquals(ResultEnum.FRIEND_REQUEST_EXISTS.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Accept Friend Request")
    class AcceptRequest {

        @Test
        @DisplayName("should create friendship and update request status")
        void shouldCreateFriendship() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            Account accepter = AccountTestBuilder.anAccountWithId(USER_B_ID);

            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);
            when(friendRequestMapper.updateById(any(FriendRequest.class))).thenReturn(1);
            when(accountService.findAccountById(USER_B_ID)).thenReturn(accepter);

            // When
            friendService.acceptRequest(USER_B_ID, encodeId(request.getId()));

            // Then
            assertEquals(FriendRequest.STATUS_ACCEPTED, request.getStatus());
            verify(friendRequestMapper).updateById(request);
        }

        @Test
        @DisplayName("should send SSE notification to requester")
        void shouldNotifyRequester() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            Account accepter = AccountTestBuilder.anAccountWithId(USER_B_ID);

            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);
            when(friendRequestMapper.updateById(any(FriendRequest.class))).thenReturn(1);
            when(accountService.findAccountById(USER_B_ID)).thenReturn(accepter);

            // When
            friendService.acceptRequest(USER_B_ID, encodeId(request.getId()));

            // Then
            verify(sseEmitterManager).sendToUser(eq(TENANT_ID), eq(USER_A_ID), any(SseEvent.class));
        }

        @Test
        @DisplayName("should reject if request not found")
        void shouldRejectIfNotFound() {
            // Given
            when(friendRequestMapper.selectById(anyLong())).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.acceptRequest(USER_B_ID, encodeId(999L)));

            assertEquals(ResultEnum.FRIEND_REQUEST_NOT_FOUND.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject if not addressee")
        void shouldRejectIfNotAddressee() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);

            Long wrongUserId = 999L;

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.acceptRequest(wrongUserId, encodeId(request.getId())));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject already processed request")
        void shouldRejectAlreadyProcessed() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.anAcceptedRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.acceptRequest(USER_B_ID, encodeId(request.getId())));

            assertEquals(ResultEnum.FRIEND_REQUEST_PROCESSED.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Reject Friend Request")
    class RejectRequest {

        @Test
        @DisplayName("should update request status to rejected")
        void shouldRejectRequest() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);
            when(friendRequestMapper.updateById(any(FriendRequest.class))).thenReturn(1);

            // When
            friendService.rejectRequest(USER_B_ID, encodeId(request.getId()));

            // Then
            assertEquals(FriendRequest.STATUS_REJECTED, request.getStatus());
            verify(friendRequestMapper).updateById(request);
        }

        @Test
        @DisplayName("should reject if not addressee")
        void shouldRejectIfNotAddressee() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.rejectRequest(USER_A_ID, encodeId(request.getId())));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Cancel Friend Request")
    class CancelRequest {

        @Test
        @DisplayName("should update request status to cancelled")
        void shouldCancelRequest() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);
            when(friendRequestMapper.updateById(any(FriendRequest.class))).thenReturn(1);

            // When
            friendService.cancelRequest(USER_A_ID, encodeId(request.getId()));

            // Then
            assertEquals(FriendRequest.STATUS_CANCELLED, request.getStatus());
            verify(friendRequestMapper).updateById(request);
        }

        @Test
        @DisplayName("should reject if not requester")
        void shouldRejectIfNotRequester() {
            // Given
            FriendRequest request = FriendRequestTestBuilder.aPendingRequest(USER_A_ID, USER_B_ID);
            when(friendRequestMapper.selectById(request.getId())).thenReturn(request);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.cancelRequest(USER_B_ID, encodeId(request.getId())));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Unfriend")
    class Unfriend {

        @Test
        @DisplayName("should remove friendship")
        void shouldRemoveFriendship() {
            // Given
            Friendship friendship = new Friendship()
                    .setId(1L)
                    .setUserA(USER_A_ID)
                    .setUserB(USER_B_ID);

            when(friendshipMapper.findByUsers(USER_A_ID, USER_B_ID, TENANT_ID)).thenReturn(friendship);
            when(friendshipMapper.deleteById(friendship.getId())).thenReturn(1);

            // When
            friendService.unfriend(USER_A_ID, encodeId(USER_B_ID));

            // Then
            verify(friendshipMapper).deleteById(friendship.getId());
        }

        @Test
        @DisplayName("should reject if not friends")
        void shouldRejectIfNotFriends() {
            // Given
            when(friendshipMapper.findByUsers(anyLong(), anyLong(), anyLong())).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> friendService.unfriend(USER_A_ID, encodeId(USER_B_ID)));

            assertEquals(ResultEnum.NOT_FRIENDS.getCode(), ex.getResultEnum().getCode());
        }
    }

    @Nested
    @DisplayName("Are Friends")
    class AreFriends {

        @Test
        @DisplayName("should return true if users are friends")
        void shouldReturnTrueIfFriends() {
            // Given
            when(friendshipMapper.areFriends(USER_A_ID, USER_B_ID, TENANT_ID)).thenReturn(1);

            // When
            boolean result = friendService.areFriends(USER_A_ID, USER_B_ID);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false if users are not friends")
        void shouldReturnFalseIfNotFriends() {
            // Given
            when(friendshipMapper.areFriends(anyLong(), anyLong(), anyLong())).thenReturn(0);

            // When
            boolean result = friendService.areFriends(USER_A_ID, USER_B_ID);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for same user")
        void shouldReturnFalseForSameUser() {
            // When
            boolean result = friendService.areFriends(USER_A_ID, USER_A_ID);

            // Then
            assertFalse(result);
            verify(friendshipMapper, never()).areFriends(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("should return false for null user")
        void shouldReturnFalseForNullUser() {
            // When
            boolean result1 = friendService.areFriends(null, USER_B_ID);
            boolean result2 = friendService.areFriends(USER_A_ID, null);

            // Then
            assertFalse(result1);
            assertFalse(result2);
            verify(friendshipMapper, never()).areFriends(anyLong(), anyLong(), anyLong());
        }
    }

    // Helper method to simulate ID encoding (matches mocked IdUtils.toExternalId behavior)
    private String encodeId(Long id) {
        return "EXT_" + id;
    }

    // Helper method to create SendFriendRequestVO (uses standard setters, not chainable)
    private SendFriendRequestVO createSendRequestVO(Long addresseeId, String message) {
        SendFriendRequestVO vo = new SendFriendRequestVO();
        vo.setAddresseeId(encodeId(addresseeId));
        vo.setMessage(message);
        return vo;
    }
}
