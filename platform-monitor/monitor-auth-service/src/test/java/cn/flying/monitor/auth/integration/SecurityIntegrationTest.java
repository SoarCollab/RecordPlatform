package cn.flying.monitor.auth.integration;

import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.security.JwtTokenProvider;
import cn.flying.monitor.common.security.MfaService;
import cn.flying.monitor.common.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for security functionality
 */
@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockBean
    private UserService userService;
    
    @MockBean
    private MfaService mfaService;
    
    @MockBean
    private PasswordEncoder passwordEncoder;
    
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    
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
    }
    
    @Test
    void testLogin_WithValidCredentials_ReturnsAccessToken() throws Exception {
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        
        Map<String, String> loginRequest = Map.of(
            "username", "testuser",
            "password", "password"
        );
        
        mockMvc.perform(post("/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("access"));
    }
    
    @Test
    void testLogin_WithMfaEnabled_ReturnsPreAuthToken() throws Exception {
        testUser.setMfaEnabled(true);
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password", "hashedPassword")).thenReturn(true);
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        
        Map<String, String> loginRequest = Map.of(
            "username", "testuser",
            "password", "password"
        );
        
        mockMvc.perform(post("/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.mfaEnabled").value(true))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.tokenType").value("pre_auth"))
                .andExpect(jsonPath("$.mfaRequired").value(true));
    }
    
    @Test
    void testLogin_WithInvalidCredentials_ReturnsBadRequest() throws Exception {
        when(userService.findByUsernameOrEmail("testuser")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpassword", "hashedPassword")).thenReturn(false);
        
        Map<String, String> loginRequest = Map.of(
            "username", "testuser",
            "password", "wrongpassword"
        );
        
        mockMvc.perform(post("/api/v2/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
    
    @Test
    void testCompleteMfa_WithValidCode_ReturnsAccessToken() throws Exception {
        testUser.setMfaEnabled(true);
        String preAuthToken = jwtTokenProvider.generatePreAuthToken("1", "testuser");
        
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyMfaCode(1L, "123456", false)).thenReturn(true);
        
        Map<String, Object> mfaRequest = Map.of(
            "preAuthToken", preAuthToken,
            "mfaCode", "123456",
            "backupCode", false
        );
        
        mockMvc.perform(post("/api/v2/auth/mfa/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mfaRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.tokenType").value("access"))
                .andExpect(jsonPath("$.mfaVerified").value(true));
    }
    
    @Test
    void testCompleteMfa_WithInvalidCode_ReturnsBadRequest() throws Exception {
        testUser.setMfaEnabled(true);
        String preAuthToken = jwtTokenProvider.generatePreAuthToken("1", "testuser");
        
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        when(mfaService.verifyMfaCode(1L, "wrong-code", false)).thenReturn(false);
        
        Map<String, Object> mfaRequest = Map.of(
            "preAuthToken", preAuthToken,
            "mfaCode", "wrong-code",
            "backupCode", false
        );
        
        mockMvc.perform(post("/api/v2/auth/mfa/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mfaRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
    
    @Test
    void testRefreshToken_WithValidToken_ReturnsNewAccessToken() throws Exception {
        String refreshToken = jwtTokenProvider.generateRefreshToken("1");
        
        when(userService.findByIdWithRoles(1L)).thenReturn(Optional.of(testUser));
        
        Map<String, String> refreshRequest = Map.of(
            "refreshToken", refreshToken
        );
        
        mockMvc.perform(post("/api/v2/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("access"));
    }
    
    @Test
    void testRefreshToken_WithInvalidToken_ReturnsBadRequest() throws Exception {
        Map<String, String> refreshRequest = Map.of(
            "refreshToken", "invalid-token"
        );
        
        mockMvc.perform(post("/api/v2/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}