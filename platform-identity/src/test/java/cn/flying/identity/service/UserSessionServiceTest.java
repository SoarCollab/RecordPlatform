package cn.flying.identity.service;

import cn.flying.identity.dto.UserSession;
import cn.flying.identity.mapper.UserSessionMapper;
import cn.flying.identity.service.impl.UserSessionServiceImpl;
import cn.flying.platformapi.constant.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户会话管理服务测试类
 * 测试会话的创建、查询、更新、失效等功能
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSessionServiceTest {

    @Spy
    @InjectMocks
    private UserSessionServiceImpl userSessionService;

    @Mock
    private UserSessionMapper userSessionMapper;

    // 测试常量
    private static final Long TEST_USER_ID = 10001L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_SESSION_ID = "session123456";
    private static final String TEST_CLIENT_IP = "192.168.1.100";
    private static final String TEST_USER_AGENT = "Mozilla/5.0";
    private static final String TEST_DEVICE_FINGERPRINT = "device123";
    private static final String TEST_LOCATION = "北京市";

    private UserSession mockSession;

    @BeforeEach
    void setUp() {
        // 创建Mock会话对象
        mockSession = createMockSession();
    }

    private UserSession createMockSession() {
        UserSession session = new UserSession();
        session.setId(1L);
        session.setSessionId(TEST_SESSION_ID);
        session.setUserId(TEST_USER_ID);
        session.setUsername(TEST_USERNAME);
        session.setClientIp(TEST_CLIENT_IP);
        session.setUserAgent(TEST_USER_AGENT);
        session.setDeviceFingerprint(TEST_DEVICE_FINGERPRINT);
        session.setLocation(TEST_LOCATION);
        session.setLoginTime(LocalDateTime.now());
        session.setLastAccessTime(LocalDateTime.now());
        session.setExpireTime(LocalDateTime.now().plusHours(24));
        session.setStatus(UserSession.Status.VALID.getCode());
        return session;
    }

    @Test
    void testCreateSession_Success() {
        // 准备测试数据
        doReturn(true).when(userSessionService).save(any(UserSession.class));

        // 执行测试
        Result<UserSession> result = userSessionService.createSession(
                TEST_USER_ID, TEST_USERNAME, TEST_CLIENT_IP,
                TEST_USER_AGENT, TEST_DEVICE_FINGERPRINT, TEST_LOCATION
        );

        // 验证结果
        assertTrue(result.isSuccess(), "Unexpected code: " + result.getCode());
        assertNotNull(result.getData());
        assertEquals(TEST_USER_ID, result.getData().getUserId());
        assertEquals(TEST_USERNAME, result.getData().getUsername());
        assertEquals(TEST_CLIENT_IP, result.getData().getClientIp());
        verify(userSessionService, times(1)).save(any(UserSession.class));
    }

    @Test
    void testCreateSession_SaveFailed() {
        // 准备测试数据
        doReturn(false).when(userSessionService).save(any(UserSession.class));

        // 执行测试
        Result<UserSession> result = userSessionService.createSession(
                TEST_USER_ID, TEST_USERNAME, TEST_CLIENT_IP,
                TEST_USER_AGENT, TEST_DEVICE_FINGERPRINT, TEST_LOCATION
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(500, result.getCode());
        assertEquals("创建会话失败", result.getMessage());
    }

    @Test
    void testCreateSession_Exception() {
        // 准备测试数据
        doThrow(new RuntimeException("Database error")).when(userSessionService).save(any(UserSession.class));

        // 执行测试
        Result<UserSession> result = userSessionService.createSession(
                TEST_USER_ID, TEST_USERNAME, TEST_CLIENT_IP,
                TEST_USER_AGENT, TEST_DEVICE_FINGERPRINT, TEST_LOCATION
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(500, result.getCode());
        assertTrue(result.getMessage().contains("创建会话异常"));
    }

    @Test
    void testFindBySessionId_Found() {
        // 准备测试数据
        when(userSessionMapper.selectOne(any())).thenReturn(mockSession);

        // 执行测试
        Result<UserSession> result = userSessionService.findBySessionId(TEST_SESSION_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_SESSION_ID, result.getData().getSessionId());
    }

    @Test
    void testFindBySessionId_NotFound() {
        // 准备测试数据
        when(userSessionMapper.selectOne(any())).thenReturn(null);

        // 执行测试
        Result<UserSession> result = userSessionService.findBySessionId(TEST_SESSION_ID);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(404, result.getCode());
        assertEquals("会话不存在", result.getMessage());
    }

    @Test
    void testFindActiveSessionsByUserId_Success() {
        // 准备测试数据
        List<UserSession> sessions = Arrays.asList(mockSession, createMockSession());
        when(userSessionMapper.findActiveSessionsByUserId(TEST_USER_ID)).thenReturn(sessions);

        // 执行测试
        Result<List<UserSession>> result = userSessionService.findActiveSessionsByUserId(TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(2, result.getData().size());
        verify(userSessionMapper, times(1)).findActiveSessionsByUserId(TEST_USER_ID);
    }

    @Test
    void testFindByUserIdAndDeviceFingerprint_Found() {
        // 准备测试数据
        when(userSessionMapper.findByUserIdAndDeviceId(TEST_USER_ID, TEST_DEVICE_FINGERPRINT))
                .thenReturn(mockSession);

        // 执行测试
        Result<UserSession> result = userSessionService.findByUserIdAndDeviceFingerprint(
                TEST_USER_ID, TEST_DEVICE_FINGERPRINT
        );

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_DEVICE_FINGERPRINT, result.getData().getDeviceFingerprint());
    }

    @Test
    void testUpdateLastAccessTime_Success() {
        // 准备测试数据
        when(userSessionMapper.updateLastActiveTime(eq(TEST_SESSION_ID), any(LocalDateTime.class)))
                .thenReturn(1);

        // 执行测试
        Result<Void> result = userSessionService.updateLastAccessTime(TEST_SESSION_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        verify(userSessionMapper, times(1)).updateLastActiveTime(eq(TEST_SESSION_ID), any(LocalDateTime.class));
    }

    @Test
    void testUpdateLastAccessTime_Failed() {
        // 准备测试数据
        when(userSessionMapper.updateLastActiveTime(eq(TEST_SESSION_ID), any(LocalDateTime.class)))
                .thenReturn(0);

        // 执行测试
        Result<Void> result = userSessionService.updateLastAccessTime(TEST_SESSION_ID);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(500, result.getCode());
    }

    @Test
    void testUpdateSessionStatus_Success() {
        // 准备测试数据
        when(userSessionMapper.updateSessionStatus(TEST_SESSION_ID, UserSession.Status.INVALID.getCode()))
                .thenReturn(1);

        // 执行测试
        Result<Void> result = userSessionService.updateSessionStatus(
                TEST_SESSION_ID, UserSession.Status.INVALID.getCode()
        );

        // 验证结果
        assertTrue(result.isSuccess());
        verify(userSessionMapper, times(1)).updateSessionStatus(
                TEST_SESSION_ID, UserSession.Status.INVALID.getCode()
        );
    }

    @Test
    void testLogoutSession_Success() {
        // 准备测试数据
        when(userSessionMapper.selectOne(any())).thenReturn(mockSession);
        when(userSessionMapper.updateById(any(UserSession.class))).thenReturn(1);

        // 执行测试
        Result<Void> result = userSessionService.logoutSession(
                TEST_SESSION_ID, UserSession.LogoutReason.USER_LOGOUT
        );

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(UserSession.Status.INVALID.getCode(), mockSession.getStatus());
        assertEquals(UserSession.LogoutReason.USER_LOGOUT.getCode(), mockSession.getLogoutReason());
        assertNotNull(mockSession.getLogoutTime());
    }

    @Test
    void testLogoutSession_NotFound() {
        // 准备测试数据
        when(userSessionMapper.selectOne(any())).thenReturn(null);

        // 执行测试
        Result<Void> result = userSessionService.logoutSession(
                TEST_SESSION_ID, UserSession.LogoutReason.USER_LOGOUT
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(404, result.getCode());
        assertEquals("会话不存在", result.getMessage());
    }

    @Test
    void testLogoutAllUserSessions_Success() {
        // 准备测试数据
        when(userSessionMapper.expireAllUserSessions(TEST_USER_ID)).thenReturn(5);

        // 执行测试
        Result<Void> result = userSessionService.logoutAllUserSessions(
                TEST_USER_ID, UserSession.LogoutReason.FORCE_LOGOUT
        );

        // 验证结果
        assertTrue(result.isSuccess());
        verify(userSessionMapper, times(1)).expireAllUserSessions(TEST_USER_ID);
    }

    @Test
    void testCleanExpiredSessions_Success() {
        // 准备测试数据
        LocalDateTime beforeTime = LocalDateTime.now().minusDays(7);
        when(userSessionMapper.cleanExpiredSessions(beforeTime)).thenReturn(10);

        // 执行测试
        Result<Integer> result = userSessionService.cleanExpiredSessions(beforeTime);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(10, result.getData());
        verify(userSessionMapper, times(1)).cleanExpiredSessions(beforeTime);
    }

    @Test
    void testIsSessionValid_Valid() {
        // 准备测试数据
        when(userSessionMapper.selectOne(any())).thenReturn(mockSession);

        // 执行测试
        Result<Boolean> result = userSessionService.isSessionValid(TEST_SESSION_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testIsSessionValid_Expired() {
        // 准备测试数据
        mockSession.setExpireTime(LocalDateTime.now().minusHours(1));
        when(userSessionMapper.selectOne(any())).thenReturn(mockSession);

        // 执行测试
        Result<Boolean> result = userSessionService.isSessionValid(TEST_SESSION_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testExtendSession_Success() {
        // 准备测试数据
        LocalDateTime newExpireTime = LocalDateTime.now().plusHours(48);
        doReturn(1).when(userSessionMapper).update(isNull(), any());

        // 执行测试
        Result<Void> result = userSessionService.extendSession(TEST_SESSION_ID, newExpireTime);

        // 验证结果
        verify(userSessionMapper, times(1)).update(isNull(), any());
        assertTrue(result.isSuccess(), "Unexpected code: " + result.getCode());
    }

    @Test
    void testCountSessionsByTimeRange_Success() {
        // 准备测试数据
        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();
        List<Map<String, Object>> stats = new ArrayList<>();
        Map<String, Object> stat = new HashMap<>();
        stat.put("date", "2025-01-16");
        stat.put("count", 100);
        stats.add(stat);
        when(userSessionMapper.countSessionsByTimeRange(startTime, endTime)).thenReturn(stats);

        // 执行测试
        Result<List<Map<String, Object>>> result = userSessionService.countSessionsByTimeRange(startTime, endTime);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals(100, result.getData().get(0).get("count"));
    }

    @Test
    void testCountSessionsByClientIp_Success() {
        // 准备测试数据
        LocalDateTime startTime = LocalDateTime.now().minusDays(7);
        LocalDateTime endTime = LocalDateTime.now();
        List<Map<String, Object>> stats = new ArrayList<>();
        Map<String, Object> stat = new HashMap<>();
        stat.put("client_ip", TEST_CLIENT_IP);
        stat.put("count", 50);
        stats.add(stat);
        when(userSessionMapper.countSessionsByLoginType(startTime, endTime)).thenReturn(stats);

        // 执行测试
        Result<List<Map<String, Object>>> result = userSessionService.countSessionsByClientIp(startTime, endTime);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(1, result.getData().size());
        assertEquals(50, result.getData().get(0).get("count"));
    }
}
