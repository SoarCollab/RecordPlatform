package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.AccountInvitation;
import cn.flying.dao.entity.AccountMemberAudit;
import cn.flying.dao.mapper.AccountInvitationMapper;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies digest-only invitation persistence and deterministic lifecycle handling. */
@ExtendWith(MockitoExtension.class)
class TenantInvitationServiceTest {

    private static final String RAW_TOKEN = "a".repeat(43);
    private static final String TOKEN_HASH = TenantInvitationService.hashToken(RAW_TOKEN);
    private static final String PASSWORD = "password-marker-42";

    @Mock private AccountInvitationMapper invitationMapper;
    @Mock private AccountMapper accountMapper;
    @Mock private TenantMapper tenantMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TenantInvitationMailSender invitationMailSender;
    @Mock private AccountMemberAuditMapper auditMapper;
    @Captor private ArgumentCaptor<LambdaQueryWrapper<AccountInvitation>> queryCaptor;
    private MockedStatic<IdUtils> ids;
    private TenantInvitationService service;

    /** Initializes entity metadata only to inspect the actual tenant-bound list query. */
    @BeforeAll
    static void initializeInvitationMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AccountInvitation.class);
    }

    /** Uses scoped ID generation and the real audit sanitizer without changing shared static configuration. */
    @BeforeEach
    void setUp() {
        AtomicLong generatedIds = new AtomicLong(100L);
        ids = mockStatic(IdUtils.class);
        ids.when(IdUtils::nextEntityId).thenAnswer(invocation -> generatedIds.incrementAndGet());
        ids.when(IdUtils::nextUserId).thenReturn(201L);
        ids.when(() -> IdUtils.toExternalId(anyLong()))
                .thenAnswer(invocation -> "E-invitation-" + invocation.getArgument(0));
        ids.when(() -> IdUtils.toExternalUserId(anyLong()))
                .thenAnswer(invocation -> "U-member-" + invocation.getArgument(0));
        service = new TenantInvitationService(
                invitationMapper, accountMapper, tenantMapper, passwordEncoder, invitationMailSender,
                new TenantMemberAuditService(auditMapper));
        ReflectionTestUtils.setField(service, "invitationAcceptUrl", "https://record.test/invitations/accept");
        lenient().when(tenantMapper.lockTenantForMemberMutation(any())).thenAnswer(
                invocation -> invocation.getArgument(0));
        lenient().when(tenantMapper.lockActiveTenantForInvitationAcceptance(anyLong())).thenAnswer(
                invocation -> invocation.getArgument(0));
    }

    /** Restores caller isolation state and the scoped ID methods after every scenario. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ids.close();
    }

    /** Creation stores only a digest and emits one matching, fragment-only capability to direct mail. */
    @Test
    void storesOnlyDigestAndDoesNotReturnToken() {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(1);
        allowAuditInsert();
        CreateTenantInvitationRequest request =
                new CreateTenantInvitationRequest("User@Example.com", "user", 24, "approved");

        var result = service.create(0L, 7L, request);

        ArgumentCaptor<AccountInvitation> invitation = ArgumentCaptor.forClass(AccountInvitation.class);
        verify(invitationMapper).insert(invitation.capture());
        assertThat(invitation.getValue().getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(invitation.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(invitation.getValue().getTenantId()).isZero();
        assertThat(invitation.getValue().getInvitedBy()).isEqualTo(7L);
        assertThat(invitation.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(invitation.getValue().getExpiresAt())
                .isEqualTo(invitation.getValue().getCreateTime().plusHours(24));
        assertThat(result.id()).isEqualTo("E-invitation-101");
        assertThat(result.toString()).doesNotContain(invitation.getValue().getTokenHash());
        ArgumentCaptor<String> invitationUrl = ArgumentCaptor.forClass(String.class);
        verify(invitationMailSender).sendInvitation(eq("user@example.com"), invitationUrl.capture());
        assertThat(invitationUrl.getValue())
                .startsWith("https://record.test/invitations/accept#token=")
                .doesNotContain("?token=");
        URI link = URI.create(invitationUrl.getValue());
        String rawToken = link.getFragment().substring("token=".length());
        assertThat(link.getQuery()).isNull();
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(rawToken)).hasSize(32);
        assertThat(TenantInvitationService.hashToken(rawToken)).isEqualTo(invitation.getValue().getTokenHash());
        assertThat(result.toString()).doesNotContain(rawToken);
        AccountMemberAudit audit = capturedAudit();
        assertThat(audit.getAction()).isEqualTo("INVITATION_CREATED");
        assertThat(audit.getInvitationId()).isEqualTo(invitation.getValue().getId());
        assertThat(audit.getTargetAccountId()).isNull();
        assertThat(audit.getReason()).isEqualTo("approved");
        assertThat(audit.getNewValue()).isEqualTo("user");
        InOrder order = inOrder(tenantMapper, invitationMapper, invitationMailSender);
        order.verify(tenantMapper).lockTenantForMemberMutation(0L);
        order.verify(invitationMapper).expirePastDueByEmail(eq(0L), eq("user@example.com"), any());
        order.verify(invitationMapper).countLiveByEmail(eq(0L), eq("user@example.com"), any());
        order.verify(invitationMapper).insert(any(AccountInvitation.class));
        order.verify(invitationMailSender).sendInvitation(eq("user@example.com"), any());
        verify(invitationMapper).expirePastDueByEmail(eq(0L), eq("user@example.com"), any());
        verify(tenantMapper).lockTenantForMemberMutation(0L);
    }

    /** Unknown capabilities fail before any tenant-owned invitation or account is accessed. */
    @Test
    void rejectsUnknownTokenBeforeTenantDataAccess() {
        when(invitationMapper.selectOwnerTenantIdByTokenHash(any())).thenReturn(null);

        assertThatThrownBy(() -> service.accept(new AcceptTenantInvitationRequest(
                "a".repeat(43), "new-user", "New User", "password123")))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.INVITATION_INVALID));

        verify(invitationMapper, never()).selectForAcceptance(any(), any());
    }

    /** Expired capabilities cannot create an account and preserve the caller's previous tenant. */
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

    /** Digest output matches a standard SHA-256 vector and is deterministic. */
    @Test
    void tokenDigestIsDeterministicAndNeverContainsRawToken() {
        String token = "opaque-token-value";
        String digest = TenantInvitationService.hashToken(token);
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").doesNotContain(token);
        assertThat(TenantInvitationService.hashToken(token)).isEqualTo(digest);
        assertThat(TenantInvitationService.hashToken("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** The authoritative duplicate-key constraint maps to the same error as the live pre-check. */
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
        verifyNoInteractions(auditMapper);
    }

    /** Expired metadata is projected without leaking either raw tokens or their digests. */
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

    /** All three tenant roles can be invited with normalized email and bounded lifetime metadata. */
    @ParameterizedTest
    @ValueSource(strings = {"user", "admin", "monitor"})
    void createsInvitationsForOnlyTenantRoles(String role) {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(1);
        allowAuditInsert();

        var view = service.create(11L, 7L,
                new CreateTenantInvitationRequest("  Member@Example.Test  ", role, 168,
                        "approved password=secret-marker"));

        assertThat(view.email()).isEqualTo("member@example.test");
        assertThat(view.role()).isEqualTo(role);
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.expiresAt()).isEqualTo(view.createTime().plusHours(168));
        assertThat(capturedAudit().getReason()).isEqualTo("approved password=******");
    }

    /** Invalid roles cannot reach the tenant fence or create a pending capability. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"platform_admin", "noop", "ADMIN", "unknown"})
    void rejectsNonTenantInvitationRole(String role) {
        assertRejected(() -> service.create(11L, 7L,
                new CreateTenantInvitationRequest("member@example.test", role, 24, "approved")),
                ResultEnum.PARAM_IS_INVALID);

        verifyNoInteractions(tenantMapper, invitationMapper, accountMapper, auditMapper, invitationMailSender);
    }

    /** Missing and negative tenant identifiers fail before checking email or invitation state. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void rejectsInvalidCreationTenant(Long tenantId) {
        assertRejected(() -> service.create(tenantId, 7L, createRequest()),
                ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(tenantMapper, invitationMapper, accountMapper, auditMapper, invitationMailSender);
    }

    /** A missing or mismatched tenant fence cannot authorize invitation creation. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void rejectsUnavailableCreationFence(Long lockedTenant) {
        when(tenantMapper.lockTenantForMemberMutation(11L)).thenReturn(lockedTenant);

        assertRejected(() -> service.create(11L, 7L, createRequest()), ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(invitationMapper, accountMapper, auditMapper, invitationMailSender);
    }

    /** Existing global account emails cannot gain an invitation in another tenant. */
    @Test
    void rejectsGlobalEmailConflictBeforeInvitationCleanup() {
        when(accountMapper.countByGlobalEmail("member@example.test")).thenReturn(1L);

        assertRejected(() -> service.create(11L, 7L, createRequest()), ResultEnum.INVITATION_ACCOUNT_CONFLICT);

        verifyNoInteractions(invitationMapper, auditMapper, invitationMailSender);
    }

    /** A current pending invitation produces a deterministic duplicate error after expiry cleanup. */
    @Test
    void rejectsLivePendingDuplicateWithoutNewTokenDelivery() {
        when(invitationMapper.countLiveByEmail(eq(11L), eq("member@example.test"), any())).thenReturn(1L);

        assertRejected(() -> service.create(11L, 7L, createRequest()), ResultEnum.INVITATION_ALREADY_EXISTS);

        verify(invitationMapper).expirePastDueByEmail(eq(11L), eq("member@example.test"), any());
        verify(invitationMapper, never()).insert(any(AccountInvitation.class));
        verifyNoInteractions(auditMapper, invitationMailSender);
    }

    /** An invitation insert must affect exactly one row before audit or mail delivery. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsUnsuccessfulInvitationInsert(int affectedRows) {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(affectedRows);

        assertThatThrownBy(() -> service.create(11L, 7L, createRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invitation persistence failed");

        verifyNoInteractions(auditMapper, invitationMailSender);
    }

    /** Failed mandatory audit persistence prevents capability delivery. */
    @Test
    void doesNotSendInvitationWhenAuditFails() {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(1);

        assertThatThrownBy(() -> service.create(11L, 7L, createRequest()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Tenant member audit persistence failed");

        verifyNoInteractions(invitationMailSender);
    }

    /** Mail failure is propagated to the transactional caller without exposing a successful response. */
    @Test
    void propagatesMailFailureWithSanitizedAuditEvidence() {
        when(invitationMapper.insert(any(AccountInvitation.class))).thenReturn(1);
        allowAuditInsert();
        IllegalStateException failure = new IllegalStateException("mail unavailable");
        doThrow(failure).when(invitationMailSender).sendInvitation(any(), any());

        assertThatThrownBy(() -> service.create(11L, 7L, createRequest())).isSameAs(failure);

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getReason()).isEqualTo("approved");
        assertThat(event.getNewValue()).isEqualTo("user");
        ids.verify(() -> IdUtils.toExternalId(anyLong()), never());
    }

    /** Invitation listings bind one tenant, sort recent first, cap their size and never expose token material. */
    @Test
    void listsBoundedTenantMetadataWithDerivedAndTerminalStatuses() {
        AccountInvitation active = invitation().setId(1L);
        AccountInvitation pastDue = invitation().setId(2L).setExpiresAt(LocalDateTime.now().minusMinutes(1));
        AccountInvitation noExpiry = invitation().setId(3L).setExpiresAt(null);
        AccountInvitation accepted = invitation().setId(4L).setStatus("ACCEPTED");
        AccountInvitation revoked = invitation().setId(5L).setStatus("REVOKED");
        AccountInvitation expired = invitation().setId(6L).setStatus("EXPIRED");
        when(invitationMapper.selectList(any()))
                .thenReturn(List.of(active, pastDue, noExpiry, accepted, revoked, expired));

        var result = service.list(11L);

        assertThat(result).extracting(view -> view.status())
                .containsExactly("PENDING", "EXPIRED", "PENDING", "ACCEPTED", "REVOKED", "EXPIRED");
        assertThat(result).allSatisfy(view -> {
            assertThat(view.id()).startsWith("E-invitation-");
            assertThat(view.toString()).doesNotContain(TOKEN_HASH, RAW_TOKEN, "tokenHash");
        });
        verify(invitationMapper).selectList(queryCaptor.capture());
        LambdaQueryWrapper<AccountInvitation> query = queryCaptor.getValue();
        assertThat(query.getSqlSegment()).contains("tenant_id =", "ORDER BY create_time DESC", "LIMIT 100");
        assertThat(query.getParamNameValuePairs()).containsValue(11L).hasSize(1);
    }

    /** Revocation writes the same sanitized reason to the lifecycle transition and audit evidence. */
    @Test
    void revokesPendingInvitationWithSanitizedReason() {
        when(invitationMapper.revokePending(eq(11L), eq(101L), eq(7L),
                eq("approved token=******"), any())).thenReturn(1);
        allowAuditInsert();

        service.revoke(11L, 7L, 101L, " approved\n token=secret-marker ");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getTenantId()).isEqualTo(11L);
        assertThat(event.getActorId()).isEqualTo(7L);
        assertThat(event.getTargetAccountId()).isNull();
        assertThat(event.getInvitationId()).isEqualTo(101L);
        assertThat(event.getAction()).isEqualTo("INVITATION_REVOKED");
        assertThat(event.getOldValue()).isEqualTo("PENDING");
        assertThat(event.getNewValue()).isEqualTo("REVOKED");
        assertThat(event.getReason()).isEqualTo("approved token=******");
        verifyNoInteractions(accountMapper, invitationMailSender);
    }

    /** A null invitation ID must not issue an unbounded revocation update. */
    @Test
    void rejectsMissingRevocationIdBeforeUpdate() {
        assertRejected(() -> service.revoke(11L, 7L, null, "approved"), ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(invitationMapper, auditMapper);
    }

    /** Consumed, missing or unexpectedly broad revocations never generate a success audit. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsRevocationWithoutExactlyOneTransition(int affectedRows) {
        when(invitationMapper.revokePending(eq(11L), eq(101L), eq(7L), eq("approved"), any()))
                .thenReturn(affectedRows);

        assertRejected(() -> service.revoke(11L, 7L, 101L, "approved"), ResultEnum.TENANT_MEMBER_NOT_FOUND);

        verifyNoInteractions(auditMapper);
    }

    /** Empty reasons cannot create or revoke an invitation. */
    @ParameterizedTest
    @ValueSource(strings = {"create", "revoke"})
    void rejectsMissingMutationReason(String operation) {
        assertRejected(() -> {
            if (operation.equals("create")) {
                service.create(11L, 7L,
                        new CreateTenantInvitationRequest("member@example.test", "user", 24, " \n "));
            } else {
                service.revoke(11L, 7L, 101L, null);
            }
        }, ResultEnum.PARAM_IS_INVALID);

        verifyNoInteractions(tenantMapper, invitationMapper, accountMapper, auditMapper, invitationMailSender);
    }

    /** Acceptance forces owner isolation for every effect, then restores the caller and rejects replay. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  Invited Member  "})
    void acceptsOnceWithOwnerIsolationAndRestoresCaller(String nickname) {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        AccountInvitation invitation = givenAcceptableInvitation();
        when(accountMapper.countByGlobalEmailOrUsername("member@example.test", "new-user"))
                .thenAnswer(invocation -> {
                    assertOwnerIsolation();
                    return 0L;
                });
        when(passwordEncoder.encode(PASSWORD)).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return "encoded-password";
        });
        when(accountMapper.insert(any(Account.class))).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return 1;
        });
        when(invitationMapper.markAccepted(eq(11L), eq(101L), eq(201L), any()))
                .thenAnswer(invocation -> {
                    assertOwnerIsolation();
                    invitation.setStatus("ACCEPTED");
                    return 1;
                });
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return 1;
        });

        TenantMemberVO view = service.accept(new AcceptTenantInvitationRequest(
                RAW_TOKEN, "  new-user  ", nickname, PASSWORD));

        ArgumentCaptor<Account> account = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).insert(account.capture());
        Account created = account.getValue();
        assertThat(created.getId()).isEqualTo(201L);
        assertThat(created.getTenantId()).isEqualTo(11L);
        assertThat(created.getUsername()).isEqualTo("new-user");
        assertThat(created.getEmail()).isEqualTo("member@example.test");
        assertThat(created.getNickname()).isEqualTo(nickname == null ? null : nickname.trim());
        assertThat(created.getPassword()).isEqualTo("encoded-password").isNotEqualTo(PASSWORD);
        assertThat(created.getRole()).isEqualTo("monitor");
        assertThat(created.getStatus()).isEqualTo(1);
        assertThat(created.getAuthVersion()).isZero();
        assertThat(created.getDeleted()).isZero();
        assertThat(created.getRegisterTime()).isNotNull();
        assertThat(created.getUpdateTime()).isNotNull();
        assertThat(view.id()).isEqualTo("U-member-201");
        assertThat(view.email()).isEqualTo(created.getEmail());
        assertThat(view.nickname()).isEqualTo(created.getNickname());
        assertThat(view.registerTime()).isEqualTo(created.getRegisterTime());
        assertThat(view.lastLoginTime()).isNull();
        assertThat(view.toString()).doesNotContain(PASSWORD, "encoded-password", RAW_TOKEN, TOKEN_HASH);
        AccountMemberAudit event = capturedAudit();
        assertThat(event.getTenantId()).isEqualTo(11L);
        assertThat(event.getActorId()).isEqualTo(201L);
        assertThat(event.getTargetAccountId()).isEqualTo(201L);
        assertThat(event.getInvitationId()).isEqualTo(101L);
        assertThat(event.getAction()).isEqualTo("INVITATION_ACCEPTED");
        assertThat(event.getOldValue()).isEqualTo("PENDING");
        assertThat(event.getNewValue()).isEqualTo("ACCEPTED");
        assertThat(event.getReason()).isEqualTo("Invitation accepted by recipient");
        assertCallerIsolationRestored();
        InOrder order = inOrder(invitationMapper, tenantMapper);
        order.verify(invitationMapper).selectOwnerTenantIdByTokenHash(TOKEN_HASH);
        order.verify(tenantMapper).lockActiveTenantForInvitationAcceptance(11L);
        order.verify(invitationMapper).selectForAcceptance(11L, TOKEN_HASH);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verify(accountMapper).insert(any(Account.class));
        assertCallerIsolationRestored();
        verifyNoInteractions(invitationMailSender);
    }

    /** Missing, inactive or mismatched owner fences reject acceptance before any invitation or account effects. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {99L})
    void rejectsUnavailableAcceptanceTenantAndRestoresCaller(Long lockedTenant) {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        when(invitationMapper.selectOwnerTenantIdByTokenHash(TOKEN_HASH)).thenReturn(11L);
        when(tenantMapper.lockActiveTenantForInvitationAcceptance(11L)).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return lockedTenant;
        });

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verify(invitationMapper, never()).selectForAcceptance(any(), any());
        verifyNoInteractions(accountMapper, passwordEncoder, auditMapper, invitationMailSender);
        assertCallerIsolationRestored();
    }

    /** A tenant-lock dependency failure restores the caller without consuming or encoding any capability. */
    @Test
    void restoresCallerContextAfterAcceptanceTenantLockFailure() {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        when(invitationMapper.selectOwnerTenantIdByTokenHash(TOKEN_HASH)).thenReturn(11L);
        IllegalStateException failure = new IllegalStateException("tenant state unavailable");
        when(tenantMapper.lockActiveTenantForInvitationAcceptance(11L)).thenThrow(failure);

        assertThatThrownBy(() -> service.accept(acceptRequest())).isSameAs(failure);

        verify(invitationMapper, never()).selectForAcceptance(any(), any());
        verifyNoInteractions(accountMapper, passwordEncoder, auditMapper, invitationMailSender);
        assertCallerIsolationRestored();
    }

    /** Invalid owner metadata stops acceptance before tenant-owned reads and credential encoding. */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L})
    void rejectsInvalidOwnerMetadata(Long tenantId) {
        when(invitationMapper.selectOwnerTenantIdByTokenHash(TOKEN_HASH)).thenReturn(tenantId);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verify(invitationMapper, never()).selectForAcceptance(any(), any());
        verifyNoInteractions(accountMapper, passwordEncoder, auditMapper);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** A deleted invitation after owner recovery fails with no account effects and restores isolation. */
    @Test
    void rejectsInvitationMissingAfterOwnerLookup() {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        when(invitationMapper.selectOwnerTenantIdByTokenHash(TOKEN_HASH)).thenReturn(11L);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verifyNoInteractions(accountMapper, passwordEncoder, auditMapper);
        assertCallerIsolationRestored();
    }

    /** Every terminal or unknown invitation state fails closed before creating a new account. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ACCEPTED", "REVOKED", "EXPIRED", "unknown"})
    void rejectsNonPendingInvitationStates(String status) {
        givenAcceptableInvitation().setStatus(status);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verifyNoInteractions(accountMapper, passwordEncoder, auditMapper);
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
    }

    /** A globally conflicting email or username cannot be reassigned through an invitation. */
    @Test
    void rejectsGlobalAccountConflictBeforePasswordEncoding() {
        givenAcceptableInvitation();
        when(accountMapper.countByGlobalEmailOrUsername("member@example.test", "new-user")).thenReturn(1L);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_ACCOUNT_CONFLICT);

        verify(accountMapper, never()).insert(any(Account.class));
        verify(invitationMapper, never()).markAccepted(any(), any(), any(), any());
        verifyNoInteractions(passwordEncoder, auditMapper);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** A concurrent account uniqueness winner produces the stable conflict error without consuming the invitation. */
    @Test
    void mapsAccountInsertRaceToConflictAndRestoresCaller() {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        givenAcceptableInvitation();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
        when(accountMapper.insert(any(Account.class))).thenThrow(new DuplicateKeyException("account uniqueness"));

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_ACCOUNT_CONFLICT);

        verify(invitationMapper, never()).markAccepted(any(), any(), any(), any());
        verifyNoInteractions(auditMapper);
        assertCallerIsolationRestored();
    }

    /** Failed account insertion cannot consume the capability or create a success audit event. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsUnsuccessfulAccountInsert(int affectedRows) {
        givenAcceptableInvitation();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
        when(accountMapper.insert(any(Account.class))).thenReturn(affectedRows);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verify(invitationMapper, never()).markAccepted(any(), any(), any(), any());
        verifyNoInteractions(auditMapper);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** A lost single-use transition fails the transactional operation rather than returning a member. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void rejectsLostAcceptanceTransition(int affectedRows) {
        givenAcceptableInvitation();
        when(passwordEncoder.encode(PASSWORD)).thenReturn("encoded-password");
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(invitationMapper.markAccepted(eq(11L), eq(101L), eq(201L), any())).thenReturn(affectedRows);

        assertRejected(() -> service.accept(acceptRequest()), ResultEnum.INVITATION_INVALID);

        verifyNoInteractions(auditMapper);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /** Unexpected dependency failure restores both tenant and isolation flags before propagating. */
    @Test
    void restoresCallerContextAfterPasswordEncoderFailure() {
        TenantContext.setTenantId(99L);
        TenantContext.setIgnoreIsolation(true);
        givenAcceptableInvitation();
        IllegalStateException failure = new IllegalStateException("encoder unavailable");
        when(passwordEncoder.encode(PASSWORD)).thenThrow(failure);

        assertThatThrownBy(() -> service.accept(acceptRequest())).isSameAs(failure);

        verify(accountMapper, never()).insert(any(Account.class));
        verifyNoInteractions(auditMapper);
        assertCallerIsolationRestored();
    }

    /** Returns a valid admin creation request without any tenant selector or temporary password. */
    private CreateTenantInvitationRequest createRequest() {
        return new CreateTenantInvitationRequest("member@example.test", "user", 24, "approved");
    }

    /** Returns a recipient request whose capability is carried only in the accepted payload. */
    private AcceptTenantInvitationRequest acceptRequest() {
        return new AcceptTenantInvitationRequest(RAW_TOKEN, "new-user", null, PASSWORD);
    }

    /** Builds persisted invitation metadata containing only the digest and no raw capability. */
    private AccountInvitation invitation() {
        LocalDateTime now = LocalDateTime.now();
        return new AccountInvitation().setId(101L).setTenantId(11L).setTokenHash(TOKEN_HASH)
                .setEmail("member@example.test").setRole("monitor").setStatus("PENDING")
                .setInvitedBy(7L).setExpiresAt(now.plusHours(24)).setCreateTime(now).setUpdateTime(now);
    }

    /** Recovers the owner narrowly and checks isolation before returning full invitation state. */
    private AccountInvitation givenAcceptableInvitation() {
        AccountInvitation invitation = invitation();
        when(invitationMapper.selectOwnerTenantIdByTokenHash(TOKEN_HASH)).thenReturn(11L);
        when(tenantMapper.lockActiveTenantForInvitationAcceptance(11L)).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return 11L;
        });
        when(invitationMapper.selectForAcceptance(11L, TOKEN_HASH)).thenAnswer(invocation -> {
            assertOwnerIsolation();
            return invitation;
        });
        return invitation;
    }

    /** Allows successful audit writes while retaining the real sanitizer and event construction. */
    private void allowAuditInsert() {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);
    }

    /** Captures the exact event delivered to audit persistence. */
    private AccountMemberAudit capturedAudit() {
        ArgumentCaptor<AccountMemberAudit> event = ArgumentCaptor.forClass(AccountMemberAudit.class);
        verify(auditMapper).insert(event.capture());
        return event.getValue();
    }

    /** Requires recovered owner scope even when the caller previously disabled tenant isolation. */
    private void assertOwnerIsolation() {
        assertThat(TenantContext.getTenantId()).isEqualTo(11L);
        assertThat(TenantContext.isIgnoreIsolation()).isFalse();
    }

    /** Checks that public acceptance does not leak recovered owner scope into its caller. */
    private void assertCallerIsolationRestored() {
        assertThat(TenantContext.getTenantId()).isEqualTo(99L);
        assertThat(TenantContext.isIgnoreIsolation()).isTrue();
    }

    /** Asserts deterministic business errors without relying on untrusted exception strings. */
    private void assertRejected(Runnable operation, ResultEnum expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(GeneralException.class,
                error -> assertThat(error.getResultEnum()).isEqualTo(expected));
    }
}
