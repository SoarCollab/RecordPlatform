package cn.flying.identity.controller;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.config.SaTokenConfig;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.exception.GlobalExceptionHandler;
import cn.flying.identity.filter.ApiGatewayProxyFilter;
import cn.flying.identity.filter.EnhancedGatewayFilter;
import cn.flying.identity.filter.SaTokenGatewayFilter;
import cn.flying.identity.filter.TrafficMonitorFilter;
import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.gateway.cache.ApiGatewayCacheManager;
import cn.flying.identity.gateway.circuitbreaker.CircuitBreakerService;
import cn.flying.identity.gateway.circuitbreaker.FallbackStrategyManager;
import cn.flying.identity.gateway.loadbalance.LoadBalanceManager;
import cn.flying.identity.gateway.pool.ApiGatewayConnectionPoolManager;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.service.GatewayMonitorService;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.identity.service.TrafficMonitorService;
import cn.flying.identity.service.apigateway.*;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.LoginStatusVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.ResultEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthController 單元測試
 * 驗證核心身份接口在全局異常策略下的行為
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@WebMvcTest(value = AuthController.class,
        excludeFilters = {@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {EnhancedGatewayFilter.class,
                        TrafficMonitorFilter.class,
                        ApiGatewayProxyFilter.class,
                        SaTokenGatewayFilter.class,
                        SaTokenConfig.class})})
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private ApplicationProperties applicationProperties;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private ApiPermissionService apiPermissionService;

    @MockBean
    private ApiRouteService apiRouteService;

    @MockBean
    private ApiCallLogService apiCallLogService;

    @MockBean
    private ApiQuotaService apiQuotaService;

    @MockBean
    private LoadBalanceManager loadBalanceManager;

    @MockBean
    private CircuitBreakerService circuitBreakerService;

    @MockBean
    private FallbackStrategyManager fallbackStrategyManager;

    @MockBean
    private ApiGatewayCacheManager apiGatewayCacheManager;

    @MockBean
    private ApiGatewayConnectionPoolManager connectionPoolManager;

    @MockBean
    private AlertService alertService;

    @MockBean
    private GatewayMonitorService gatewayMonitorService;

    @MockBean
    private JwtBlacklistService jwtBlacklistService;

    @MockBean
    private TrafficMonitorService trafficMonitorService;

    private static final String API_LOGIN = "/api/auth/sessions";
    private static final String API_LOGOUT = "/api/auth/sessions/current";
    private static final String API_REGISTER = "/api/auth/users";
    private static final String API_VERIFY_CODE = "/api/auth/verification-codes";
    private static final String API_RESET_PASSWORD = "/api/auth/passwords/reset";
    private static final String API_CHANGE_PASSWORD = "/api/auth/passwords/current";
    private static final String API_ME = "/api/auth/me";
    private static final String API_USERS_SEARCH = "/api/auth/users/search";
    private static final String API_SESSION_STATUS = "/api/auth/sessions/status";
    private static final String API_TOKEN_INFO = "/api/auth/tokens/info";

    private static final String TEST_USERNAME = "test-user";
    private static final String TEST_PASSWORD = "Test123456";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_TOKEN = "jwt-token";

    @BeforeEach
    void setUp() {
        ApplicationProperties.Password passwordConfig = new ApplicationProperties.Password();
        passwordConfig.setMinLength(6);
        passwordConfig.setMaxLength(50);
        when(applicationProperties.getPassword()).thenReturn(passwordConfig);
    }

    @Test
    void testLogin_Success() throws Exception {
        when(authService.login(TEST_USERNAME, TEST_PASSWORD)).thenReturn(TEST_TOKEN);

        Map<String, String> body = new HashMap<>();
        body.put("username", TEST_USERNAME);
        body.put("password", TEST_PASSWORD);

        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(TEST_TOKEN));

        verify(authService).login(TEST_USERNAME, TEST_PASSWORD);
    }

    @Test
    void testLogin_InvalidLength() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("username", "a".repeat(101));
        body.put("password", TEST_PASSWORD);

        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_ServiceFailure() throws Exception {
        when(authService.login(TEST_USERNAME, TEST_PASSWORD))
                .thenThrow(new BusinessException(ResultEnum.USER_LOGIN_ERROR));

        Map<String, String> body = new HashMap<>();
        body.put("username", TEST_USERNAME);
        body.put("password", TEST_PASSWORD);

        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_LOGIN_ERROR.getCode()));
    }

    @Test
    void testLogout_Success() throws Exception {
        mockMvc.perform(delete(API_LOGOUT))
                .andExpect(status().isNoContent());

        verify(authService).logout();
    }

    @Test
    void testSearchUser_BlankQuery_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get(API_USERS_SEARCH)
                        .param("query", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        verify(authService, never()).findUserWithMasking(anyString());
    }

    @Test
    void testRegister_Success() throws Exception {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setUsername(TEST_USERNAME);
        vo.setPassword(TEST_PASSWORD);
        vo.setEmail(TEST_EMAIL);
        vo.setCode("123456");

        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).register(any(EmailRegisterVO.class));
    }

    @Test
    void testSendVerificationCode_InvalidEmail() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", "invalid-email");
        body.put("type", "register");

        mockMvc.perform(post(API_VERIFY_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        verify(authService, never()).askVerifyCode(anyString(), anyString());
    }

    @Test
    void testSendVerificationCode_Success() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", TEST_EMAIL);
        body.put("type", "register");

        mockMvc.perform(post(API_VERIFY_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).askVerifyCode(TEST_EMAIL, "register");
    }

    @Test
    void testResetPassword_Success() throws Exception {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail(TEST_EMAIL);
        vo.setCode("123456");
        vo.setPassword("NewPass1234");

        mockMvc.perform(put(API_RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).resetConfirm(any(EmailResetVO.class));
    }

    @Test
    void testChangePassword_ServiceThrows() throws Exception {
        ChangePasswordVO vo = new ChangePasswordVO();
        vo.setPassword("OldPassword123");
        vo.setNewPassword("NewPassword123");

        doThrow(new BusinessException(ResultEnum.USER_NOT_LOGGED_IN))
                .when(authService).changePassword(any(ChangePasswordVO.class));

        mockMvc.perform(put(API_CHANGE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vo)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_NOT_LOGGED_IN.getCode()));
    }

    @Test
    void testGetCurrentUser_Success() throws Exception {
        AccountVO accountVO = new AccountVO();
        accountVO.setUsername(TEST_USERNAME);
        accountVO.setEmail(TEST_EMAIL);
        when(authService.getUserInfo()).thenReturn(accountVO);

        mockMvc.perform(get(API_ME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME));
    }

    @Test
    void testSearchUser_Unauthorized() throws Exception {
        when(authService.findUserWithMasking(TEST_USERNAME))
                .thenThrow(new BusinessException(ResultEnum.PERMISSION_UNAUTHORIZED));

        mockMvc.perform(get(API_USERS_SEARCH).param("query", TEST_USERNAME))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));
    }

    @Test
    void testGetSessionStatus_Success() throws Exception {
        when(authService.checkLoginStatus()).thenReturn(new LoginStatusVO(true, "已登录", 1L));

        mockMvc.perform(get(API_SESSION_STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loggedIn").value(true))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testGetTokenInfo_Unauthorized() throws Exception {
        when(authService.getTokenInfo())
                .thenThrow(new BusinessException(ResultEnum.USER_NOT_LOGGED_IN));

        mockMvc.perform(get(API_TOKEN_INFO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_NOT_LOGGED_IN.getCode()));
    }
}
