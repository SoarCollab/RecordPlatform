package cn.flying.identity.service;

import cn.flying.identity.service.impl.SmsServiceImpl;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.dromara.sms4j.api.SmsBlend;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.dromara.sms4j.core.factory.SmsFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 短信服务测试类
 * 测试短信验证码发送、普通短信发送、服务可用性检查等功能
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsServiceTest {

    @InjectMocks
    private SmsServiceImpl smsService;

    // 测试常量
    private static final String TEST_PHONE = "13800138000";
    private static final String TEST_CODE = "123456";
    private static final String TEST_TYPE = "register";
    private static final String TEST_CONTENT = "This is a test SMS message";
    private static final String TEST_SUPPLIER = "alibaba";

    @BeforeEach
    void setUp() {
        // 设置默认配置
        ReflectionTestUtils.setField(smsService, "defaultSupplier", "mock");
        ReflectionTestUtils.setField(smsService, "smsRestricted", false);
    }

    @Test
    void testSendVerifyCode_Success() {
        // Mock SMS4J框架
        try (MockedStatic<SmsFactory> smsFactory = mockStatic(SmsFactory.class)) {
            SmsBlend mockSmsBlend = mock(SmsBlend.class);
            SmsResponse mockResponse = mock(SmsResponse.class);

            smsFactory.when(() -> SmsFactory.getSmsBlend(anyString())).thenReturn(mockSmsBlend);
            when(mockSmsBlend.sendMessage(TEST_PHONE, TEST_CODE)).thenReturn(mockResponse);
            when(mockResponse.isSuccess()).thenReturn(true);

            // 执行测试
            Result<Boolean> result = smsService.sendVerifyCode(TEST_PHONE, TEST_CODE, TEST_TYPE);

            // 验证结果
            assertTrue(result.isSuccess());
            assertTrue(result.getData());
            verify(mockSmsBlend, times(1)).sendMessage(TEST_PHONE, TEST_CODE);
        }
    }

    @Test
    void testSendVerifyCode_WithSupplier_Success() {
        // Mock SMS4J框架
        try (MockedStatic<SmsFactory> smsFactory = mockStatic(SmsFactory.class)) {
            SmsBlend mockSmsBlend = mock(SmsBlend.class);
            SmsResponse mockResponse = mock(SmsResponse.class);

            smsFactory.when(() -> SmsFactory.getSmsBlend(TEST_SUPPLIER)).thenReturn(mockSmsBlend);
            when(mockSmsBlend.sendMessage(TEST_PHONE, TEST_CODE)).thenReturn(mockResponse);
            when(mockResponse.isSuccess()).thenReturn(true);

            // 执行测试
            Result<Boolean> result = smsService.sendVerifyCode(TEST_PHONE, TEST_CODE, TEST_TYPE, TEST_SUPPLIER);

            // 验证结果
            assertTrue(result.isSuccess());
            assertTrue(result.getData());
            verify(mockSmsBlend, times(1)).sendMessage(TEST_PHONE, TEST_CODE);
        }
    }

    @Test
    void testSendVerifyCode_Restricted() {
        // 设置短信功能为禁用
        ReflectionTestUtils.setField(smsService, "smsRestricted", true);

        // 执行测试
        Result<Boolean> result = smsService.sendVerifyCode(TEST_PHONE, TEST_CODE, TEST_TYPE);

        // 验证结果 - 虽然被禁用，但仍返回成功
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testSendVerifyCode_InvalidParams() {
        // 测试空手机号
        Result<Boolean> result = smsService.sendVerifyCode("", TEST_CODE, TEST_TYPE);
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());

        // 测试空验证码
        result = smsService.sendVerifyCode(TEST_PHONE, "", TEST_TYPE);
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());

        // 测试空类型
        result = smsService.sendVerifyCode(TEST_PHONE, TEST_CODE, "");
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testSendVerifyCode_SendFailed() {
        // Mock SMS4J框架
        try (MockedStatic<SmsFactory> smsFactory = mockStatic(SmsFactory.class)) {
            SmsBlend mockSmsBlend = mock(SmsBlend.class);
            SmsResponse mockResponse = mock(SmsResponse.class);

            smsFactory.when(() -> SmsFactory.getSmsBlend(anyString())).thenReturn(mockSmsBlend);
            when(mockSmsBlend.sendMessage(TEST_PHONE, TEST_CODE)).thenReturn(mockResponse);
            when(mockResponse.isSuccess()).thenReturn(false);
            when(mockResponse.toString()).thenReturn("Send failed: insufficient balance");

            // 执行测试
            Result<Boolean> result = smsService.sendVerifyCode(TEST_PHONE, TEST_CODE, TEST_TYPE);

            // 验证结果
            assertFalse(result.isSuccess());
            assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
        }
    }

    @Test
    void testSendMessage_Success() {
        // Mock SMS4J框架
        try (MockedStatic<SmsFactory> smsFactory = mockStatic(SmsFactory.class)) {
            SmsBlend mockSmsBlend = mock(SmsBlend.class);
            SmsResponse mockResponse = mock(SmsResponse.class);

            smsFactory.when(() -> SmsFactory.getSmsBlend(anyString())).thenReturn(mockSmsBlend);
            when(mockSmsBlend.sendMessage(TEST_PHONE, TEST_CONTENT)).thenReturn(mockResponse);
            when(mockResponse.isSuccess()).thenReturn(true);

            // 执行测试
            Result<Boolean> result = smsService.sendMessage(TEST_PHONE, TEST_CONTENT);

            // 验证结果
            assertTrue(result.isSuccess());
            assertTrue(result.getData());
            verify(mockSmsBlend, times(1)).sendMessage(TEST_PHONE, TEST_CONTENT);
        }
    }

    @Test
    void testSendMessage_Restricted() {
        // 设置短信功能为禁用
        ReflectionTestUtils.setField(smsService, "smsRestricted", true);

        // 执行测试
        Result<Boolean> result = smsService.sendMessage(TEST_PHONE, TEST_CONTENT);

        // 验证结果 - 虽然被禁用，但仍返回成功
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testIsServiceAvailable_Available() {
        // Mock SMS4J框架
        try (MockedStatic<SmsFactory> smsFactory = mockStatic(SmsFactory.class)) {
            SmsBlend mockSmsBlend = mock(SmsBlend.class);
            smsFactory.when(() -> SmsFactory.getSmsBlend("mock")).thenReturn(mockSmsBlend);

            // 执行测试
            Result<Boolean> result = smsService.isServiceAvailable("mock");

            // 验证结果
            assertTrue(result.isSuccess());
            assertTrue(result.getData());
        }
    }

    @Test
    void testIsServiceAvailable_Restricted() {
        // 设置短信功能为禁用
        ReflectionTestUtils.setField(smsService, "smsRestricted", true);

        // 执行测试
        Result<Boolean> result = smsService.isServiceAvailable("mock");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testIsServiceAvailable_UnsupportedSupplier() {
        // 执行测试
        Result<Boolean> result = smsService.isServiceAvailable("unsupported");

        // 验证结果
        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testGetAvailableSuppliers_Success() {
        // 执行测试
        Result<List<String>> result = smsService.getAvailableSuppliers();

        // 验证结果
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertTrue(result.getData().contains("alibaba"));
        assertTrue(result.getData().contains("tencent"));
        assertTrue(result.getData().contains("huawei"));
        assertTrue(result.getData().contains("mock"));
        assertEquals(4, result.getData().size());
    }

    @Test
    void testGetDefaultSupplier() {
        // 执行测试
        String defaultSupplier = smsService.getDefaultSupplier();

        // 验证结果
        assertEquals("mock", defaultSupplier);
    }

    @Test
    void testIsSmsRestricted() {
        // 测试未限制状态
        assertFalse(smsService.isSmsRestricted());

        // 设置为限制状态
        ReflectionTestUtils.setField(smsService, "smsRestricted", true);
        assertTrue(smsService.isSmsRestricted());
    }
}