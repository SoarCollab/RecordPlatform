package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.AccountInvitation;
import cn.flying.dao.mapper.AccountInvitationMapper;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies digest-only invitation persistence and deterministic lifecycle handling. */
@ExtendWith(MockitoExtension.class)
class TenantInvitationServiceTest {

    @Mock private AccountInvitationMapper invitationMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantInvitationMailSender invitationMailSender;
    @Mock private TenantMemberAuditService auditService;
    private TenantInvitationService service;

    @BeforeEach
    void setUp() {
        SnowflakeIdGenerator snowflake = mock(SnowflakeIdGenerator.class);
        SecureIdCodec codec = mock(SecureIdCodec.class);
        lenient().when(snowflake.nextId()).thenReturn(101L, 102L);
        lenient().when(codec.toExternalId(any())).thenReturn("E-invitation");
        lenient().when(codec.toExternalUserId(any())).thenReturn("U-member");
        new IdUtils(snowflake, codec);
        service = new TenantInvitationService(
                invitationMapper, accountMapper, passwordEncoder, invitationMailSender, auditService);
        ReflectionTestUtils.setField(service, "invitationAcceptUrl", "https://record.test/invitations/accept");
        lenient().when(auditService.sanitizeReason("approved")).thenReturn("approved");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void storesOnlyDigestAndDoesNotReturnToken() {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(1);
        CreateTenantInvitationRequest request =
                new CreateTenantInvitationRequest("User@Example.com", "user", 24, "approved");

        var result = service.create(0L, 7L, request);

        ArgumentCaptor<AccountInvitation> invitation = ArgumentCaptor.forClass(AccountInvitation.class);
        verify(invitationMapper).insert(invitation.capture());
        assertThat(invitation.getValue().getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(invitation.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(result.toString()).doesNotContain(invitation.getValue().getTokenHash());
        ArgumentCaptor<String> invitationUrl = ArgumentCaptor.forClass(String.class);
        verify(invitationMailSender).sendInvitation(eq("user@example.com"), invitationUrl.capture());
        assertThat(invitationUrl.getValue())
                .startsWith("https://record.test/invitations/accept#token=")
                .doesNotContain("?token=");
        verify(invitationMapper).expirePastDueByEmail(eq(0L), eq("user@example.com"), any());
    }

    @Test
    void rejectsUnknownTokenBeforeTenantDataAccess() {
        when(invitationMapper.selectOwnerTenantIdByTokenHash(any())).thenReturn(null);

        assertThatThrownBy(() -> service.accept(new AcceptTenantInvitationRequest(
                "a".repeat(43), "new-user", "New User", "password123")))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.INVITATION_INVALID));

        verify(invitationMapper, never()).selectForAcceptance(any(), any());
    }

    @Test
    void rejectsExpiredInvitationAndRestoresCallerContext() {
        TenantContext.setTenantId(99L);
        when(invitationMapper.selectOwnerTenantIdByTokenHash(any())).thenReturn(11L);
        AccountInvitation invitation = new AccountInvitation()
                .setId(1L).setTenantId(11L).setStatus("PENDING")
                .setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(invitationMapper.selectForAcceptance(eq(11L), any())).thenReturn(invitation);

        assertThatThrownBy(() -> service.accept(new AcceptTenantInvitationRequest(
                "b".repeat(43), "new-user", null, "password123")))
                .isInstanceOf(GeneralException.class);

        assertThat(TenantContext.requireTenantId()).isEqualTo(99L);
        verify(accountMapper, never()).insert(any(cn.flying.dao.dto.Account.class));
    }

    @Test
    void tokenDigestIsDeterministicAndNeverContainsRawToken() {
        String token = "opaque-token-value";
        String digest = TenantInvitationService.hashToken(token);
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(token);
        assertThat(TenantInvitationService.hashToken(token)).isEqualTo(digest);
    }

    @Test
    void mapsConcurrentPendingInsertToDeterministicDuplicateResult() {
        when(invitationMapper.insert(any(AccountInvitation.class)))
                .thenThrow(new DuplicateKeyException("pending invitation guard"));

        assertThatThrownBy(() -> service.create(11L, 7L,
                new CreateTenantInvitationRequest("user@example.com", "user", 24, "approved")))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum())
                                .isEqualTo(ResultEnum.INVITATION_ALREADY_EXISTS));

        verify(invitationMailSender, never()).sendInvitation(any(), any());
    }

    @Test
    void rendersPastDuePendingInvitationAsExpiredWithoutTokenMaterial() {
        AccountInvitation invitation = new AccountInvitation()
                .setId(1L)
                .setTenantId(11L)
                .setEmail("expired@example.test")
                .setRole("user")
                .setStatus("PENDING")
                .setExpiresAt(LocalDateTime.now().minusMinutes(1))
                .setCreateTime(LocalDateTime.now().minusHours(2));
        when(invitationMapper.selectList(any())).thenReturn(List.of(invitation));

        var result = service.list(11L);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo("EXPIRED");
            assertThat(view.toString()).doesNotContain("token");
        });
    }
}
