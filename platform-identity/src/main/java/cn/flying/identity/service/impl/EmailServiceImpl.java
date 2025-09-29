package cn.flying.identity.service.impl;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.service.EmailService;
import jakarta.annotation.Resource;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * 邮件服务实现类
 * 从 platform-backend 迁移而来，提供邮件发送功能
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    @Resource
    private JavaMailSender mailSender;

    @Resource
    private ApplicationProperties applicationProperties;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Override
    public boolean sendVerifyCode(String email, String code, String type) {
        try {
            String subject;
            String content;

            if ("register".equals(type)) {
                subject = "RecordPlatform - 注册验证码";
                content = buildRegisterEmailContent(code);
            } else if ("reset".equals(type)) {
                subject = "RecordPlatform - 密码重置验证码";
                content = buildResetEmailContent(code);
            } else {
                subject = "RecordPlatform - 验证码";
                content = buildDefaultEmailContent(code);
            }

            return sendHtmlMail(email, subject, content);
        } catch (Exception e) {
            log.error("发送验证码邮件失败，邮箱: {}, 类型: {}", email, type, e);
            return false;
        }
    }

    @Override
    public boolean sendSimpleMail(String to, String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);

            mailSender.send(message);
            log.info("简单邮件发送成功，收件人: {}, 主题: {}", to, subject);
            return true;
        } catch (Exception e) {
            log.error("简单邮件发送失败，收件人: {}, 主题: {}", to, subject, e);
            return false;
        }
    }

    @Override
    public boolean sendHtmlMail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("HTML邮件发送成功，收件人: {}, 主题: {}", to, subject);
            return true;
        } catch (Exception e) {
            log.error("HTML邮件发送失败，收件人: {}, 主题: {}", to, subject, e);
            return false;
        }
    }

    /**
     * 构建注册验证码邮件内容
     */
    private String buildRegisterEmailContent(String code) {
        int expireMinutes = applicationProperties.getVerifyCode().getExpireMinutes();
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>注册验证码</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #2c3e50;">RecordPlatform 注册验证码</h2>
                        <p>您好！</p>
                        <p>感谢您注册 RecordPlatform 存证平台。您的验证码是：</p>
                        <div style="background-color: #f8f9fa; padding: 20px; text-align: center; margin: 20px 0; border-radius: 5px;">
                            <span style="font-size: 24px; font-weight: bold; color: #007bff; letter-spacing: 2px;">%s</span>
                        </div>
                        <p style="color: #666;">验证码有效期为 %d 分钟，请及时使用。</p>
                        <p style="color: #666;">如果这不是您的操作，请忽略此邮件。</p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
                        <p style="color: #999; font-size: 12px;">此邮件由系统自动发送，请勿回复。</p>
                    </div>
                </body>
                </html>
                """, code, expireMinutes);
    }

    /**
     * 构建密码重置验证码邮件内容
     */
    private String buildResetEmailContent(String code) {
        int expireMinutes = applicationProperties.getVerifyCode().getExpireMinutes();
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>密码重置验证码</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #e74c3c;">RecordPlatform 密码重置</h2>
                        <p>您好！</p>
                        <p>您正在重置 RecordPlatform 存证平台的登录密码。您的验证码是：</p>
                        <div style="background-color: #fff5f5; padding: 20px; text-align: center; margin: 20px 0; border-radius: 5px; border: 1px solid #fed7d7;">
                            <span style="font-size: 24px; font-weight: bold; color: #e74c3c; letter-spacing: 2px;">%s</span>
                        </div>
                        <p style="color: #666;">验证码有效期为 %d 分钟，请及时使用。</p>
                        <p style="color: #e74c3c; font-weight: bold;">如果这不是您的操作，请立即联系客服或更改密码！</p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
                        <p style="color: #999; font-size: 12px;">此邮件由系统自动发送，请勿回复。</p>
                    </div>
                </body>
                </html>
                """, code, expireMinutes);
    }

    /**
     * 构建默认验证码邮件内容
     */
    private String buildDefaultEmailContent(String code) {
        int expireMinutes = applicationProperties.getVerifyCode().getExpireMinutes();
        return String.format("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>验证码</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #2c3e50;">RecordPlatform 验证码</h2>
                        <p>您好！</p>
                        <p>您的验证码是：</p>
                        <div style="background-color: #f8f9fa; padding: 20px; text-align: center; margin: 20px 0; border-radius: 5px;">
                            <span style="font-size: 24px; font-weight: bold; color: #28a745; letter-spacing: 2px;">%s</span>
                        </div>
                        <p style="color: #666;">验证码有效期为 %d 分钟，请及时使用。</p>
                        <p style="color: #666;">如果这不是您的操作，请忽略此邮件。</p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
                        <p style="color: #999; font-size: 12px;">此邮件由系统自动发送，请勿回复。</p>
                    </div>
                </body>
                </html>
                """, code, expireMinutes);
    }
}
