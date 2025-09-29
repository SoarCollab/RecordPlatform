package cn.flying.identity.service;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.service.impl.EmailServiceImpl;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 邮件服务测试类
 * 测试邮件发送功能，包括验证码邮件、简单邮件、HTML邮件等
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailServiceTest {

    @InjectMocks
    private EmailServiceImpl emailService;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private ApplicationProperties applicationProperties;

    @Mock
    private MimeMessage mimeMessage;

    // 测试常量
    private static final String TEST_EMAIL = "test@example.com";
    private static final String FROM_EMAIL = "noreply@example.com";
    private static final String TEST_CODE = "123456";
    private static final String TEST_SUBJECT = "Test Subject";
    private static final String TEST_CONTENT = "Test Content";

    @BeforeEach
    void setUp() {
        // 注入fromEmail字段
        ReflectionTestUtils.setField(emailService, "fromEmail", FROM_EMAIL);

        // Mock ApplicationProperties
        ApplicationProperties.VerifyCode verifyCode = new ApplicationProperties.VerifyCode();
        verifyCode.setExpireMinutes(5);
        when(applicationProperties.getVerifyCode()).thenReturn(verifyCode);
    }

    @Test
    void testSendVerifyCode_Register_Success() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_Reset_Success() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, TEST_CODE, "reset");

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_Default_Success() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, TEST_CODE, "other");

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_SendFailed() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("Mail server error")).when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertFalse(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendSimpleMail_Success() {
        // 准备测试数据
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // 执行测试
        boolean result = emailService.sendSimpleMail(TEST_EMAIL, TEST_SUBJECT, TEST_CONTENT);

        // 验证结果
        assertTrue(result);

        // 验证邮件内容
        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage capturedMessage = messageCaptor.getValue();

        assertEquals(FROM_EMAIL, capturedMessage.getFrom());
        assertArrayEquals(new String[]{TEST_EMAIL}, capturedMessage.getTo());
        assertEquals(TEST_SUBJECT, capturedMessage.getSubject());
        assertEquals(TEST_CONTENT, capturedMessage.getText());
    }

    @Test
    void testSendSimpleMail_SendFailed() {
        // 准备测试数据
        doThrow(new RuntimeException("Mail server error")).when(mailSender).send(any(SimpleMailMessage.class));

        // 执行测试
        boolean result = emailService.sendSimpleMail(TEST_EMAIL, TEST_SUBJECT, TEST_CONTENT);

        // 验证结果
        assertFalse(result);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void testSendHtmlMail_Success() {
        // 准备测试数据
        String htmlContent = "<html><body><h1>Test</h1></body></html>";
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendHtmlMail(TEST_EMAIL, TEST_SUBJECT, htmlContent);

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendHtmlMail_SendFailed() {
        // 准备测试数据
        String htmlContent = "<html><body><h1>Test</h1></body></html>";
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new RuntimeException("Mail server error")).when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendHtmlMail(TEST_EMAIL, TEST_SUBJECT, htmlContent);

        // 验证结果
        assertFalse(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_NullEmail() {
        // 执行测试
        boolean result = emailService.sendVerifyCode(null, TEST_CODE, "register");

        // 验证结果
        assertFalse(result);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_EmptyCode() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试 - 即使code为空，邮件仍会发送（业务逻辑决定）
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, "", "register");

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendSimpleMail_MultipleRecipients() {
        // 准备测试数据
        String multipleEmails = "test1@example.com,test2@example.com";
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // 执行测试
        boolean result = emailService.sendSimpleMail(multipleEmails, TEST_SUBJECT, TEST_CONTENT);

        // 验证结果
        assertTrue(result);

        // 验证邮件发送
        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage capturedMessage = messageCaptor.getValue();

        assertEquals(FROM_EMAIL, capturedMessage.getFrom());
        assertEquals(TEST_SUBJECT, capturedMessage.getSubject());
        assertEquals(TEST_CONTENT, capturedMessage.getText());
    }

    @Test
    void testSendHtmlMail_ComplexHtml() {
        // 准备测试数据
        String complexHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Complex HTML</title>
                </head>
                <body>
                    <h1>Welcome</h1>
                    <p>This is a <strong>complex</strong> HTML email.</p>
                    <ul>
                        <li>Item 1</li>
                        <li>Item 2</li>
                    </ul>
                </body>
                </html>
                """;
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendHtmlMail(TEST_EMAIL, TEST_SUBJECT, complexHtml);

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendVerifyCode_LongCode() {
        // 准备测试数据
        String longCode = "123456789012345678901234567890";
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doNothing().when(mailSender).send(any(MimeMessage.class));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, longCode, "register");

        // 验证结果
        assertTrue(result);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void testSendSimpleMail_EmptyContent() {
        // 准备测试数据
        doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        // 执行测试
        boolean result = emailService.sendSimpleMail(TEST_EMAIL, TEST_SUBJECT, "");

        // 验证结果
        assertTrue(result);

        // 验证邮件内容
        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        SimpleMailMessage capturedMessage = messageCaptor.getValue();

        assertEquals("", capturedMessage.getText());
    }

    @Test
    void testSendVerifyCode_CreateMimeMessageFailed() {
        // 准备测试数据
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Cannot create MIME message"));

        // 执行测试
        boolean result = emailService.sendVerifyCode(TEST_EMAIL, TEST_CODE, "register");

        // 验证结果
        assertFalse(result);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}