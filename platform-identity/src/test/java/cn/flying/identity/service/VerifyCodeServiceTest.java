package cn.flying.identity.service;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.service.impl.VerifyCodeServiceImpl;
import cn.flying.identity.util.FlowUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.CaptchaUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证码服务测试类
 * 测试邮件验证码、短信验证码、图形验证码等功能
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerifyCodeServiceTest {

    @InjectMocks
    private VerifyCodeServiceImpl verifyCodeService;

    @Mock
    private ApplicationProperties applicationProperties;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EmailService emailService;

    @Mock
    private SmsService smsService;

    @Mock
    private FlowUtils flowUtils;

    // 测试常量
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PHONE = "13800138000";
    private static final String TEST_CODE = "123456";
    private static final String TEST_IP = "127.0.0.1";
    private static final String TEST_SESSION_ID = "session123";

    @BeforeEach
    void setUp() {
        // Mock Redis operations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // Mock ApplicationProperties
        ApplicationProperties.VerifyCode verifyCode = new ApplicationProperties.VerifyCode();
        verifyCode.setLength(6);
        verifyCode.setExpireMinutes(5);
        when(applicationProperties.getVerifyCode()).thenReturn(verifyCode);
    }

    @Test
    void testSendEmailVerifyCode_Success() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
        when(emailService.sendVerifyCode(eq(TEST_EMAIL), anyString(), eq("register"))).thenReturn(true);
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试
        Result<Void> result = verifyCodeService.sendEmailVerifyCode(TEST_EMAIL, "register", TEST_IP);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(ResultEnum.SUCCESS.getCode(), result.getCode());
        verify(emailService, times(1)).sendVerifyCode(eq(TEST_EMAIL), anyString(), eq("register"));
        verify(valueOperations, times(1)).set(anyString(), anyString(), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void testSendEmailVerifyCode_RateLimitExceeded() {
        // 准备测试数据 - 频率限制
        when(flowUtils.limitCountCheck(anyString(), anyInt(), anyInt())).thenReturn(false);

        // 执行测试
        Result<Void> result = verifyCodeService.sendEmailVerifyCode(TEST_EMAIL, "register", TEST_IP);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_BUSY.getCode(), result.getCode());
        verify(emailService, never()).sendVerifyCode(anyString(), anyString(), anyString());
    }

    @Test
    void testSendEmailVerifyCode_SendFailed() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
        when(emailService.sendVerifyCode(eq(TEST_EMAIL), anyString(), eq("register"))).thenReturn(false);

        // 执行测试
        Result<Void> result = verifyCodeService.sendEmailVerifyCode(TEST_EMAIL, "register", TEST_IP);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testVerifyEmailCode_Success() {
        // 准备测试数据
        when(valueOperations.get("verify:email:register:" + TEST_EMAIL)).thenReturn(TEST_CODE);
        doReturn(true).when(redisTemplate).delete("verify:email:register:" + TEST_EMAIL);

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifyEmailCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
        verify(redisTemplate, times(1)).delete("verify:email:register:" + TEST_EMAIL);
    }

    @Test
    void testVerifyEmailCode_WrongCode() {
        // 准备测试数据
        when(valueOperations.get("verify:email:register:" + TEST_EMAIL)).thenReturn("654321");

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifyEmailCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void testVerifyEmailCode_CodeNotFound() {
        // 准备测试数据
        when(valueOperations.get("verify:email:register:" + TEST_EMAIL)).thenReturn(null);

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifyEmailCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void testSendSmsVerifyCode_Success() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
        when(smsService.sendVerifyCode(eq(TEST_PHONE), anyString(), eq("register")))
                .thenReturn(Result.success(true));
        when(smsService.getDefaultSupplier()).thenReturn("Aliyun");
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试
        Result<Void> result = verifyCodeService.sendSmsVerifyCode(TEST_PHONE, "register", TEST_IP);

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(ResultEnum.SUCCESS.getCode(), result.getCode());
        verify(smsService, times(1)).sendVerifyCode(eq(TEST_PHONE), anyString(), eq("register"));
        verify(valueOperations, times(1)).set(anyString(), anyString(), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void testSendSmsVerifyCode_SendFailed() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);
        when(smsService.sendVerifyCode(eq(TEST_PHONE), anyString(), eq("register")))
                .thenReturn(Result.error(ResultEnum.SYSTEM_ERROR, null));

        // 执行测试
        Result<Void> result = verifyCodeService.sendSmsVerifyCode(TEST_PHONE, "register", TEST_IP);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void testVerifySmsCode_Success() {
        // 准备测试数据
        when(valueOperations.get("verify:sms:register:" + TEST_PHONE)).thenReturn(TEST_CODE);
        doReturn(true).when(redisTemplate).delete("verify:sms:register:" + TEST_PHONE);

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifySmsCode(TEST_PHONE, TEST_CODE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
        verify(redisTemplate, times(1)).delete("verify:sms:register:" + TEST_PHONE);
    }

    @Test
    void testVerifySmsCode_WrongCode() {
        // 准备测试数据
        when(valueOperations.get("verify:sms:register:" + TEST_PHONE)).thenReturn("654321");

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifySmsCode(TEST_PHONE, TEST_CODE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void testGenerateImageCaptcha_Success() {
        // Mock CaptchaUtil
        try (MockedStatic<CaptchaUtil> captchaUtil = mockStatic(CaptchaUtil.class)) {
            LineCaptcha mockCaptcha = mock(LineCaptcha.class);
            when(mockCaptcha.getCode()).thenReturn("ABCD");
            when(mockCaptcha.getImageBase64()).thenReturn("base64ImageData");
            captchaUtil.when(() -> CaptchaUtil.createLineCaptcha(130, 48, 4, 20))
                    .thenReturn(mockCaptcha);

            doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            // 执行测试
            Result<Map<String, Object>> result = verifyCodeService.generateImageCaptcha(TEST_SESSION_ID);

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals(TEST_SESSION_ID, data.get("session_id"));
            assertEquals("data:image/png;base64,base64ImageData", data.get("image"));
            assertEquals(5, data.get("expire_minutes"));
            verify(valueOperations, times(1)).set(eq("verify:image:" + TEST_SESSION_ID),
                    eq("abcd"), eq(5L), eq(TimeUnit.MINUTES));
        }
    }

    @Test
    void testVerifyImageCaptcha_Success() {
        // 准备测试数据
        when(valueOperations.get("verify:image:" + TEST_SESSION_ID)).thenReturn("abcd");
        doReturn(true).when(redisTemplate).delete("verify:image:" + TEST_SESSION_ID);

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifyImageCaptcha(TEST_SESSION_ID, "ABCD");

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
        verify(redisTemplate, times(1)).delete("verify:image:" + TEST_SESSION_ID);
    }

    @Test
    void testVerifyImageCaptcha_WrongCode() {
        // 准备测试数据
        when(valueOperations.get("verify:image:" + TEST_SESSION_ID)).thenReturn("abcd");

        // 执行测试
        Result<Boolean> result = verifyCodeService.verifyImageCaptcha(TEST_SESSION_ID, "WXYZ");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void testClearVerifyCode_Success() {
        // 准备测试数据
        doReturn(true).when(redisTemplate).delete("verify:email:register:" + TEST_EMAIL);
        doReturn(true).when(redisTemplate).delete("verify:sms:register:" + TEST_EMAIL);

        // 执行测试
        Result<Void> result = verifyCodeService.clearVerifyCode(TEST_EMAIL, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        verify(redisTemplate, times(2)).delete(anyString());
    }

    @Test
    void testGetVerifyCodeStats_Success() {
        // 准备测试数据
        when(valueOperations.get("verify:count:" + TEST_EMAIL)).thenReturn("5");

        // 执行测试
        Result<Map<String, Object>> result = verifyCodeService.getVerifyCodeStats(TEST_EMAIL, 60);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> stats = result.getData();
        assertEquals(TEST_EMAIL, stats.get("identifier"));
        assertEquals(5, stats.get("send_count"));
        assertEquals(60, stats.get("time_range"));
        assertEquals(10, stats.get("max_limit_per_hour"));
    }

    @Test
    void testCheckSendLimit_EmailSuccess() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(eq("verify:limit:identifier:" + TEST_EMAIL), eq(10), eq(3600)))
                .thenReturn(true);
        when(flowUtils.limitCountCheck(eq("verify:limit:ip:" + TEST_IP), eq(30), eq(3600)))
                .thenReturn(true);
        when(flowUtils.limitOnceCheck(eq("verify:limit:once:" + TEST_EMAIL + ":register"), eq(60)))
                .thenReturn(true);

        // 执行测试
        Result<Boolean> result = verifyCodeService.checkSendLimit(TEST_EMAIL, "register", TEST_IP);

        // 验证结果
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testCheckSendLimit_PhoneRateLimitExceeded() {
        // 准备测试数据
        when(flowUtils.limitCountCheck(eq("verify:limit:identifier:" + TEST_PHONE), eq(5), eq(3600)))
                .thenReturn(false);

        // 执行测试
        Result<Boolean> result = verifyCodeService.checkSendLimit(TEST_PHONE, "register", TEST_IP);

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testGetVerifyCodeTtl_EmailCodeExists() {
        // 准备测试数据
        when(redisTemplate.getExpire("verify:email:register:" + TEST_EMAIL, TimeUnit.SECONDS))
                .thenReturn(120L);

        // 执行测试
        Result<Long> result = verifyCodeService.getVerifyCodeTtl(TEST_EMAIL, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(120L, result.getData());
    }

    @Test
    void testGetVerifyCodeTtl_SmsCodeExists() {
        // 准备测试数据
        when(redisTemplate.getExpire("verify:email:register:" + TEST_PHONE, TimeUnit.SECONDS))
                .thenReturn(-2L);
        when(redisTemplate.getExpire("verify:sms:register:" + TEST_PHONE, TimeUnit.SECONDS))
                .thenReturn(60L);

        // 执行测试
        Result<Long> result = verifyCodeService.getVerifyCodeTtl(TEST_PHONE, "register");

        // 验证结果
        assertTrue(result.isSuccess());
        assertEquals(60L, result.getData());
    }

    @Test
    void testCleanExpiredCodes_Success() {
        // 执行测试
        Result<Map<String, Object>> result = verifyCodeService.cleanExpiredCodes();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(0, data.get("cleaned_count"));
        assertNotNull(data.get("clean_time"));
    }

    @Test
    void testGetVerifyCodeConfig_Success() {
        // 执行测试
        Result<Map<String, Object>> result = verifyCodeService.getVerifyCodeConfig();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> config = result.getData();

        // 验证邮件配置
        Map<String, Object> emailConfig = (Map<String, Object>) config.get("email");
        assertEquals(6, emailConfig.get("length"));
        assertEquals(5, emailConfig.get("expire_minutes"));
        assertEquals(10, emailConfig.get("limit_per_hour"));

        // 验证短信配置
        Map<String, Object> smsConfig = (Map<String, Object>) config.get("sms");
        assertEquals(6, smsConfig.get("length"));
        assertEquals(5, smsConfig.get("expire_minutes"));
        assertEquals(5, smsConfig.get("limit_per_hour"));

        // 验证图形验证码配置
        Map<String, Object> imageConfig = (Map<String, Object>) config.get("image");
        assertEquals(130, imageConfig.get("width"));
        assertEquals(48, imageConfig.get("height"));
        assertEquals(5, imageConfig.get("expire_minutes"));
    }
}