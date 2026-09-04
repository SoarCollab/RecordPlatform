package cn.flying.listener;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.service.admin.TenantInvitationMailSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends raw invitation capability URLs directly without durable message-queue storage. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DirectTenantInvitationMailSender implements TenantInvitationMailSender {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String senderAddress;

    /** Delivers the one-time URL while keeping it out of application logs and broker storage. */
    @Override
    public void sendInvitation(String email, String invitationUrl) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setSubject("存证平台租户成员邀请");
        message.setText("管理员邀请您加入存证平台。请在邀请有效期内打开以下一次性链接完成注册：\n"
                + invitationUrl);
        message.setTo(email);
        message.setFrom(senderAddress);
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            log.error("Invitation delivery failed: exceptionType={}", exception.getClass().getSimpleName());
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }
}
