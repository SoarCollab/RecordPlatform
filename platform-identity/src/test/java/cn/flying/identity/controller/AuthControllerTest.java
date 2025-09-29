package cn.flying.identity.controller;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.config.SaTokenConfig;
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
import cn.flying.identity.util.InputValidator;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 认证控制器单元测试
 * 测试范围：用户认证、注册、密码管理、用户信息获取等RESTful API
 *
 * @author 王贝强
 * @create 2025-01-14
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
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private ApplicationProperties applicationProperties;

    // ApiGatewayProxyFilter 依赖的服务 - 需要 Mock 以避免 ApplicationContext 加载失败
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

    // EnhancedGatewayFilter 依赖的服务 - 需要 Mock 以避免 ApplicationContext 加载失败
    @MockBean
    private GatewayMonitorService gatewayMonitorService;

    @MockBean
    private JwtBlacklistService jwtBlacklistService;

    // TrafficMonitorFilter 依赖的服务 - 需要 Mock 以避免 ApplicationContext 加载失败
    @MockBean
    private TrafficMonitorService trafficMonitorService;

    @Autowired
    private ObjectMapper objectMapper;

    // 测试数据常量
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Test123456";
    private static final String TEST_TOKEN = "test-jwt-token-123";
    private static final String TEST_CODE = "123456";
    private static final Long TEST_USER_ID = 789L;
    private static final String TEST_EXTERNAL_ID = "ext_789";

    // API路径常量
    private static final String API_LOGIN = "/api/auth/sessions";
    private static final String API_LOGOUT = "/api/auth/sessions/current";
    private static final String API_REGISTER = "/api/auth/users";
    private static final String API_VERIFY_CODE = "/api/auth/verification-codes";
    private static final String API_RESET_PASSWORD = "/api/auth/passwords/reset";
    private static final String API_CHANGE_PASSWORD = "/api/auth/passwords/current";
    private static final String API_USER_INFO = "/api/auth/me";
    private static final String API_FIND_USER = "/api/auth/users/search";
    private static final String API_STATUS = "/api/auth/sessions/status";
    private static final String API_TOKEN_INFO = "/api/auth/tokens/info";

    @BeforeEach
    void setUp() {
        // 配置 ApplicationProperties
        ApplicationProperties.Password passwordConfig = new ApplicationProperties.Password();
        passwordConfig.setMinLength(6);
        passwordConfig.setMaxLength(50);
        when(applicationProperties.getPassword()).thenReturn(passwordConfig);

        // 预配置通用Mock行为
    }

    // ==================== 登录接口测试 ====================

    @Test
    void testLogin_Success() throws Exception {
        // Mock服务层返回成功
        when(authService.login(TEST_USERNAME, TEST_PASSWORD))
                .thenReturn(Result.success(TEST_TOKEN));

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);

        // 执行测试
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(TEST_TOKEN));

        // 验证服务层被调用
        verify(authService).login(TEST_USERNAME, TEST_PASSWORD);
    }

    @Test
    void testLogin_SqlInjectionDetected() throws Exception {
        // SQL注入输入
        String maliciousUsername = "admin'; DROP TABLE users; --";

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", maliciousUsername);
        loginRequest.put("password", TEST_PASSWORD);

        // 执行测试 - 应该被输入验证拦截
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_XssDetected() throws Exception {
        // XSS攻击输入
        String xssUsername = "<script>alert('xss')</script>";

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", xssUsername);
        loginRequest.put("password", TEST_PASSWORD);

        // 执行测试 - 应该被输入验证拦截
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_UsernameTooLong() throws Exception {
        // 创建超长用户名（超过100字符）
        String longUsername = "a".repeat(101);

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", longUsername);
        loginRequest.put("password", TEST_PASSWORD);

        // 执行测试
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_PasswordTooLong() throws Exception {
        // 创建超长密码（超过128字符）
        String longPassword = "a".repeat(129);

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", longPassword);

        // 执行测试
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_MissingUsername() throws Exception {
        // 缺少用户名参数
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("password", TEST_PASSWORD);

        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_MissingPassword() throws Exception {
        // 缺少密码参数
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);

        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).login(anyString(), anyString());
    }

    @Test
    void testLogin_ServiceFailure() throws Exception {
        // Mock服务层返回失败
        when(authService.login(TEST_USERNAME, TEST_PASSWORD))
                .thenReturn(Result.error(ResultEnum.USER_LOGIN_ERROR));

        // 准备JSON请求体
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);

        // 执行测试
        mockMvc.perform(post(API_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_LOGIN_ERROR.getCode()));

        // 验证服务层被调用
        verify(authService).login(TEST_USERNAME, TEST_PASSWORD);
    }

    // ==================== 注销接口测试 ====================

    @Test
    void testLogout_Success() throws Exception {
        // Mock服务层返回成功
        when(authService.logout()).thenReturn(Result.success());

        // 执行测试
        mockMvc.perform(delete(API_LOGOUT))
                .andExpect(status().isNoContent());

        // 验证服务层被调用
        verify(authService).logout();
    }

    @Test
    void testLogout_ServiceFailure() throws Exception {
        // Mock服务层返回失败
        when(authService.logout()).thenReturn(Result.error(ResultEnum.SYSTEM_ERROR));

        // 执行测试
        mockMvc.perform(delete(API_LOGOUT))
                .andExpect(status().isInternalServerError());

        // 验证服务层被调用
        verify(authService).logout();
    }

    // ==================== 注册接口测试 ====================

    @Test
    void testRegister_Success() throws Exception {
        // 准备测试数据
        EmailRegisterVO registerVO = createValidRegisterVO();

        // Mock服务层返回成功
        when(authService.register(any(EmailRegisterVO.class)))
                .thenReturn(Result.success());

        // 执行测试
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        // 验证服务层被调用
        verify(authService).register(any(EmailRegisterVO.class));
    }

    @Test
    void testRegister_InvalidEmail() throws Exception {
        // 准备无效邮箱的测试数据
        EmailRegisterVO registerVO = createValidRegisterVO();
        registerVO.setEmail("invalid-email");

        // 执行测试 - 应该被@Email验证拦截
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).register(any(EmailRegisterVO.class));
    }

    @Test
    void testRegister_InvalidUsername() throws Exception {
        // 准备包含特殊字符的用户名
        EmailRegisterVO registerVO = createValidRegisterVO();
        registerVO.setUsername("user@name"); // 包含特殊字符

        // 执行测试 - 应该被@Pattern验证拦截
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).register(any(EmailRegisterVO.class));
    }

    @Test
    void testRegister_UsernameTooLong() throws Exception {
        // 准备超长用户名（超过10字符）
        EmailRegisterVO registerVO = createValidRegisterVO();
        registerVO.setUsername("verylongusername"); // 16字符，超过限制

        // 执行测试 - 应该被@Length验证拦截
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).register(any(EmailRegisterVO.class));
    }

    @Test
    void testRegister_InvalidVerificationCode() throws Exception {
        // 准备无效验证码（不是6位）
        EmailRegisterVO registerVO = createValidRegisterVO();
        registerVO.setCode("12345"); // 5位，应该是6位

        // 执行测试 - 应该被@Length验证拦截
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).register(any(EmailRegisterVO.class));
    }

    @Test
    void testRegister_MissingFields() throws Exception {
        // 准备缺少必填字段的数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        // 缺少email、password、code

        // 执行测试 - 应该被Bean Validation拦截
        mockMvc.perform(post(API_REGISTER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).register(any(EmailRegisterVO.class));
    }

    // ==================== 发送验证码接口测试 ====================

    @Test
    void testSendVerifyCode_RegisterType_Success() throws Exception {
        // Mock输入验证
        try (MockedStatic<InputValidator> inputValidator = mockStatic(InputValidator.class)) {
            inputValidator.when(() -> InputValidator.isValidEmail(TEST_EMAIL)).thenReturn(true);

            // Mock服务层返回成功
            when(authService.askVerifyCode(TEST_EMAIL, "register"))
                    .thenReturn(Result.success());

            // 准备JSON请求体
            Map<String, String> request = new HashMap<>();
            request.put("email", TEST_EMAIL);
            request.put("type", "register");

            // 执行测试
            mockMvc.perform(post(API_VERIFY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true));

            // 验证服务层被调用
            verify(authService).askVerifyCode(TEST_EMAIL, "register");
        }
    }

    @Test
    void testSendVerifyCode_ResetType_Success() throws Exception {
        // Mock输入验证
        try (MockedStatic<InputValidator> inputValidator = mockStatic(InputValidator.class)) {
            inputValidator.when(() -> InputValidator.isValidEmail(TEST_EMAIL)).thenReturn(true);

            // Mock服务层返回成功
            when(authService.askVerifyCode(TEST_EMAIL, "reset"))
                    .thenReturn(Result.success());

            // 准备JSON请求体
            Map<String, String> request = new HashMap<>();
            request.put("email", TEST_EMAIL);
            request.put("type", "reset");

            // 执行测试
            mockMvc.perform(post(API_VERIFY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.success").value(true));

            // 验证服务层被调用
            verify(authService).askVerifyCode(TEST_EMAIL, "reset");
        }
    }

    @Test
    void testSendVerifyCode_InvalidEmail() throws Exception {
        // Mock输入验证返回无效
        try (MockedStatic<InputValidator> inputValidator = mockStatic(InputValidator.class)) {
            inputValidator.when(() -> InputValidator.isValidEmail("invalid-email")).thenReturn(false);

            // 准备JSON请求体
            Map<String, String> request = new HashMap<>();
            request.put("email", "invalid-email");
            request.put("type", "register");

            // 执行测试
            mockMvc.perform(post(API_VERIFY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

            // 验证服务层未被调用
            verify(authService, never()).askVerifyCode(anyString(), anyString());
        }
    }

    @Test
    void testSendVerifyCode_InvalidType() throws Exception {
        // Mock输入验证
        try (MockedStatic<InputValidator> inputValidator = mockStatic(InputValidator.class)) {
            inputValidator.when(() -> InputValidator.isValidEmail(TEST_EMAIL)).thenReturn(true);

            // 准备JSON请求体
            Map<String, String> request = new HashMap<>();
            request.put("email", TEST_EMAIL);
            request.put("type", "invalid_type");

            // 执行测试 - 使用无效的type参数
            mockMvc.perform(post(API_VERIFY_CODE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

            // 验证服务层未被调用
            verify(authService, never()).askVerifyCode(anyString(), anyString());
        }
    }

    // ==================== 重置密码接口测试 ====================

    @Test
    void testResetPassword_Success() throws Exception {
        // 准备测试数据
        EmailResetVO resetVO = createValidResetVO();

        // Mock服务层返回成功
        when(authService.resetConfirm(any(EmailResetVO.class)))
                .thenReturn(Result.success());

        // 执行测试
        mockMvc.perform(put(API_RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetVO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 验证服务层被调用
        verify(authService).resetConfirm(any(EmailResetVO.class));
    }

    @Test
    void testResetPassword_InvalidEmail() throws Exception {
        // 准备无效邮箱的测试数据
        EmailResetVO resetVO = createValidResetVO();
        resetVO.setEmail("invalid-email");

        // 执行测试 - 应该被@Email验证拦截
        mockMvc.perform(put(API_RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).resetConfirm(any(EmailResetVO.class));
    }

    @Test
    void testResetPassword_InvalidCode() throws Exception {
        // 准备无效验证码的测试数据
        EmailResetVO resetVO = createValidResetVO();
        resetVO.setCode("12345"); // 5位，应该是6位

        // 执行测试 - 应该被@Length验证拦截
        mockMvc.perform(put(API_RESET_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resetVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).resetConfirm(any(EmailResetVO.class));
    }

    // ==================== 修改密码接口测试 ====================

    @Test
    void testChangePassword_Success() throws Exception {
        // 准备测试数据
        ChangePasswordVO changeVO = createValidChangePasswordVO();

        // Mock服务层返回成功
        when(authService.changePassword(any(ChangePasswordVO.class)))
                .thenReturn(Result.success());

        // 执行测试
        mockMvc.perform(put(API_CHANGE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changeVO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 验证服务层被调用
        verify(authService).changePassword(any(ChangePasswordVO.class));
    }

    @Test
    void testChangePassword_MissingFields() throws Exception {
        // 准备缺少字段的数据
        ChangePasswordVO changeVO = new ChangePasswordVO();
        changeVO.setPassword(TEST_PASSWORD);
        // 缺少newPassword

        // 执行测试 - 应该被Bean Validation拦截
        mockMvc.perform(put(API_CHANGE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changeVO)))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).changePassword(any(ChangePasswordVO.class));
    }

    // ==================== 获取用户信息接口测试 ====================

    @Test
    void testGetUserInfo_Success() throws Exception {
        // 准备测试数据
        AccountVO accountVO = createMockAccountVO();

        // Mock服务层返回成功
        when(authService.getUserInfo())
                .thenReturn(Result.success(accountVO));

        // 执行测试
        mockMvc.perform(get(API_USER_INFO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.data.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.data.externalId").value(TEST_EXTERNAL_ID));

        // 验证服务层被调用
        verify(authService).getUserInfo();
    }

    @Test
    void testGetUserInfo_NotLoggedIn() throws Exception {
        // Mock服务层返回未登录错误
        when(authService.getUserInfo())
                .thenReturn(Result.error(ResultEnum.USER_NOT_LOGGED_IN));

        // 执行测试
        mockMvc.perform(get(API_USER_INFO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_NOT_LOGGED_IN.getCode()));

        // 验证服务层被调用
        verify(authService).getUserInfo();
    }

    // ==================== 查找用户接口测试 ====================

    @Test
    void testFindUser_Success() throws Exception {
        // 准备测试数据
        AccountVO accountVO = createMockAccountVO();

        // Mock服务层返回成功
        when(authService.findUserWithMasking(TEST_USERNAME))
                .thenReturn(Result.success(accountVO));

        // 执行测试
        mockMvc.perform(get(API_FIND_USER)
                        .param("query", TEST_USERNAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME));

        // 验证服务层被调用
        verify(authService).findUserWithMasking(TEST_USERNAME);
    }

    @Test
    void testFindUser_MissingParameter() throws Exception {
        // 缺少text参数
        mockMvc.perform(get(API_FIND_USER))
                .andExpect(status().isBadRequest());

        // 验证服务层未被调用
        verify(authService, never()).findUserWithMasking(anyString());
    }

    // ==================== 检查登录状态接口测试 ====================

    @Test
    void testCheckLoginStatus_Success() throws Exception {
        // 准备测试数据
        Map<String, Object> statusInfo = new HashMap<>();
        statusInfo.put("logged_in", true);
        statusInfo.put("user_id", TEST_USER_ID);

        // Mock服务层返回成功
        when(authService.checkLoginStatus())
                .thenReturn(Result.success(statusInfo));

        // 执行测试
        mockMvc.perform(get(API_STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.logged_in").value(true))
                .andExpect(jsonPath("$.data.user_id").value(TEST_USER_ID));

        // 验证服务层被调用
        verify(authService).checkLoginStatus();
    }

    // ==================== 获取Token信息接口测试 ====================

    @Test
    void testGetTokenInfo_Success() throws Exception {
        // 准备测试数据
        Map<String, Object> tokenInfo = new HashMap<>();
        tokenInfo.put("token", TEST_TOKEN);
        tokenInfo.put("expire_time", System.currentTimeMillis() + 3600000);

        // Mock服务层返回成功
        when(authService.getTokenInfo())
                .thenReturn(Result.success(tokenInfo));

        // 执行测试
        mockMvc.perform(get(API_TOKEN_INFO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value(TEST_TOKEN));

        // 验证服务层被调用
        verify(authService).getTokenInfo();
    }

    @Test
    void testGetTokenInfo_NotLoggedIn() throws Exception {
        // Mock服务层返回未登录错误
        when(authService.getTokenInfo())
                .thenReturn(Result.error(ResultEnum.USER_NOT_LOGGED_IN));

        // 执行测试
        mockMvc.perform(get(API_TOKEN_INFO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.USER_NOT_LOGGED_IN.getCode()));

        // 验证服务层被调用
        verify(authService).getTokenInfo();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建有效的注册VO
     */
    private EmailRegisterVO createValidRegisterVO() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setUsername(TEST_USERNAME);
        vo.setEmail(TEST_EMAIL);
        vo.setPassword(TEST_PASSWORD);
        vo.setCode(TEST_CODE);
        return vo;
    }

    /**
     * 创建有效的重置密码VO
     */
    private EmailResetVO createValidResetVO() {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail(TEST_EMAIL);
        vo.setCode(TEST_CODE);
        vo.setPassword(TEST_PASSWORD);
        return vo;
    }

    /**
     * 创建有效的修改密码VO
     */
    private ChangePasswordVO createValidChangePasswordVO() {
        ChangePasswordVO vo = new ChangePasswordVO();
        vo.setPassword("OldPassword123");
        vo.setNewPassword("NewPassword456");
        return vo;
    }

    /**
     * 创建模拟的账户VO
     */
    private AccountVO createMockAccountVO() {
        AccountVO vo = new AccountVO();
        vo.setId(TEST_USER_ID);
        vo.setExternalId(TEST_EXTERNAL_ID);
        vo.setUsername(TEST_USERNAME);
        vo.setEmail(TEST_EMAIL);
        vo.setRole("USER");
        vo.setAvatar("https://example.com/avatar.jpg");
        vo.setRegisterTime(new Date());
        return vo;
    }
}