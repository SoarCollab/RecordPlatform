package cn.flying.monitor.auth.service;

import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.security.JwtTokenProvider;
import cn.flying.monitor.common.security.MfaService;
import cn.flying.monitor.common.security.RbacService;
import cn.flying.monitor.common.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;


import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for authentication service with MFA support
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {
    
    @Mock
    private UserService userService;
    
    @Mock
    private MfaService mfaService;
    
    @Mock
    private RbacService rbacService;
    
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    
    @Mock
    private PasswordEncoder passwordEncoder;
    
    @InjectMocks
    private AuthenticationService authenticationService;
    
    private User testUser;
    
    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("hashedPassword");
        testUser.setStatus(User.UserStatus.ACTIVE);
        testUser.setMfaEnabled(false);
        testUser.setFailedLoginAttempts(0);
    }
    
    @Test
    void testAuthenticate_WithValidCredentialsNoMfa_ReturnsAccessToken() {
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(rbacService.getUserPermissions(testUser)).thenReturn(List.of("client:read"));
        when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), anyList(), eq(false), eq(false)))
            .thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        
        Map<String, Object> result = authenticationService.authenticate("testuser", "password", "192.168.1.1");
        
        assertEquals(1L, result.get("userId"));
        assertEquals("testuser", result.get("username"));
        assertEquals(false, result.get("mfaEnabled"));
        assertEquals("access-token", result.get("accessToken"));
        assertEquals("refresh-token", result.get("refreshToken"));
        assertEquals("access", result.get("tokenType"));
        assertEquals(false, result.get("mfaRequired"));
        
        verify(userService).resetFailedLoginAttempts(1L);
        verify(userService).updateLastLogin(1L, "192.168.1.1");
    }
    
    @Test
    void testAuthenticate_WithValidCredentialsWithMfa_ReturnsPreAuthToken() {
        testUser.setMfaEnabled(true);
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(jwtTokenProvider.generatePreAuthToken("1", "testuser")).thenReturn("pre-auth-token");
        
        Map<String, Object> result = authenticationService.authenticate("testuser", "password", "192.168.1.1");
        
        assertEquals(1L, result.get("userId"));
        assertEquals("testuser", result.get("username"));
        assertEquals(true, result.get("mfaEnabled"));
        assertEquals("pre-auth-token", result.get("token"));
        assertEquals("pre_auth", result.get("tokenType"));
        assertEquals(true, result.get("mfaRequired"));
        
        verify(userService).resetFailedLoginAttempts(1L);
        verify(userService, never()).updateLastLogin(anyLong(), anyString());
    }
    
    @Test
    void testAuthenticate_WithInvalidUser_ThrowsBadCredentialsException() {
        when(userService.findByUsernameOrEmail("nonexistent")).thenReturn(Optional.empty());
        
        assertThrows(BadCredentialsException.class, () -> 
            authenticationService.authenticate("nonexistent", "password", "192.168.1.1"));
    }
    
    @Test
    void testAuthenticate_WithLockedAccount_ThrowsLockedException() {
        testUser.setStatus(User.UserStatus.LOCKED);
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        
        assertThrows(LockedException.class, () -> 
            authenticationService.authenticate("testuser", "password", "192.168.1.1"));
    }
    
    @Test
    void testAuthenticate_WithInvalidPassword_ThrowsBadCredentialsException() {
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpassword", "hashedPassword")).thenReturn(false);
        
        assertThrows(BadCredentialsException.class, () -> 
            authenticationService.authenticate("testuser", "wrongpassword", "192.168.1.1"));
        
        verify(userService).incrementFailedLoginAttempts(1L);
    }
    
    @Test
    void testCompleteMfaAuthentication_WithValidCode_ReturnsAccessToken() {
        testUser.setMfaEnabled(true);
        when(jwtTokenProvider.validateToken("pre-auth-token")).thenReturn(true);
        when(jwtTokenProvider.isPreAuthToken("pre-auth-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("pre-auth-token")).thenReturn("1");
        when(jwtTokenProvider.getUsername("pre-auth-token")).thenReturn("testuser");
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyMfaCode(1L, "123456", false)).thenReturn(true);
        when(rbacService.getUserPermissions(testUser)).thenReturn(List.of("client:read"));
        when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), anyList(), eq(true), eq(true)))
            .thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken("1")).thenReturn("refresh-token");
        
        Map<String, Object> result = authenticationService.completeMfaAuthentication(
            "pre-auth-token", "123456", false, "192.168.1.1");
        
        assertEquals("access-token", result.get("accessToken"));
        assertEquals("refresh-token", result.get("refreshToken"));
        assertEquals("access", result.get("tokenType"));
        assertEquals(true, result.get("mfaVerified"));
        
        verify(userService).updateLastLogin(1L, "192.168.1.1");
    }
    
    @Test
    void testCompleteMfaAuthentication_WithInvalidToken_ThrowsBadCredentialsException() {
        when(jwtTokenProvider.validateToken("invalid-token")).thenReturn(false);
        
        assertThrows(BadCredentialsException.class, () -> 
            authenticationService.completeMfaAuthentication("invalid-token", "123456", false, "192.168.1.1"));
    }
    
    @Test
    void testCompleteMfaAuthentication_WithInvalidMfaCode_ThrowsBadCredentialsException() {
        testUser.setMfaEnabled(true);
        when(jwtTokenProvider.validateToken("pre-auth-token")).thenReturn(true);
        when(jwtTokenProvider.isPreAuthToken("pre-auth-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("pre-auth-token")).thenReturn("1");
        when(jwtTokenProvider.getUsername("pre-auth-token")).thenReturn("testuser");
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyMfaCode(1L, "wrong-code", false)).thenReturn(false);
        
        assertThrows(BadCredentialsException.class, () -> 
            authenticationService.completeMfaAuthentication("pre-auth-token", "wrong-code", false, "192.168.1.1"));
    }
    
    @Test
    void testRefreshToken_WithValidRefreshToken_ReturnsNewAccessToken() {
        when(jwtTokenProvider.validateToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("refresh-token")).thenReturn("1");
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(rbacService.getUserPermissions(testUser)).thenReturn(List.of("client:read"));
        when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), anyList(), eq(false), eq(false)))
            .thenReturn("new-access-token");
        
        Map<String, Object> result = authenticationService.refreshToken("refresh-token");
        
        assertEquals("new-access-token", result.get("accessToken"));
        assertEquals("access", result.get("tokenType"));
    }
    
    @Test
    void testRefreshToken_WithInvalidRefreshToken_ThrowsBadCredentialsException() {
        when(jwtTokenProvider.validateToken("invalid-refresh-token")).thenReturn(false);
        
        assertThrows(BadCredentialsException.class, () -> 
            authenticationService.refreshToken("invalid-refresh-token"));
    }
    
    @Test
    void testRefreshToken_WithLockedAccount_ThrowsLockedException() {
        testUser.setStatus(User.UserStatus.LOCKED);
        when(jwtTokenProvider.validateToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.isRefreshToken("refresh-token")).thenReturn(true);
        when(jwtTokenProvider.getUserId("refresh-token")).thenReturn("1");
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        
        assertThrows(LockedException.class, () -> 
            authenticationService.refreshToken("refresh-token"));
    }
}