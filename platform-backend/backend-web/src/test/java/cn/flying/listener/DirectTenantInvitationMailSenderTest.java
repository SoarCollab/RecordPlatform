package cn.flying.listener;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

/** Verifies direct invitation delivery without introducing a durable broker payload. */
class DirectTenantInvitationMailSenderTest {

    @Test
    void sendsTheFragmentCapabilityOnlyToTheIntendedRecipient() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        DirectTenantInvitationMailSender sender = new DirectTenantInvitationMailSender(mailSender);
        ReflectionTestUtils.setField(sender, "senderAddress", "noreply@example.test");

        sender.sendInvitation("member@example.test",
                "https://record.test/invitations/accept#token=opaque");

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("member@example.test");
        assertThat(message.getValue().getFrom()).isEqualTo("noreply@example.test");
        assertThat(message.getValue().getText())
                .contains("#token=opaque")
                .doesNotContain("?token=");
    }

    @Test
    void replacesSecretBearingMailFailuresWithAStableBusinessError() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        DirectTenantInvitationMailSender sender = new DirectTenantInvitationMailSender(mailSender);
        ReflectionTestUtils.setField(sender, "senderAddress", "noreply@example.test");
        doThrow(new MailSendException("failed #token=raw-secret"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> sender.sendInvitation(
                "member@example.test", "https://record.test/#token=raw-secret"))
                .hasMessageNotContaining("raw-secret");
    }
}
