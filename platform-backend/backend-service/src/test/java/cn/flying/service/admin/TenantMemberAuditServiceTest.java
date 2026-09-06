package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.AccountMemberAudit;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies the mandatory, secret-free tenant member audit reason boundary. */
@ExtendWith(MockitoExtension.class)
class TenantMemberAuditServiceTest {

    @Mock private AccountMemberAuditMapper auditMapper;
    private MockedStatic<IdUtils> ids;
    private TenantMemberAuditService service;

    /** Isolates generated audit IDs without changing the application's global ID configuration. */
    @BeforeEach
    void setUp() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        MDC.clear();
        ids = mockStatic(IdUtils.class);
        ids.when(IdUtils::nextEntityId).thenReturn(901L);
        service = new TenantMemberAuditService(auditMapper);
    }

    /** Releases scoped IDs and authentication state after every test, including failing assertions. */
    @AfterEach
    void tearDown() {
        try {
            ids.close();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            MDC.clear();
        }
    }

    /** Inline passwords and invitation capabilities are never retained as audit reasons. */
    @Test
    void masksSecretLikeReasonContentBeforePersistence() {
        String sanitized = service.sanitizeReason("approved token=raw-secret password=hunter2");

        assertThat(sanitized)
                .doesNotContain("raw-secret")
                .doesNotContain("hunter2");
    }

    /** Control-only reasons cannot become empty persisted evidence after sanitization. */
    @Test
    void rejectsControlOnlyReasonWithStructuredParameterError() {
        assertThatThrownBy(() -> service.sanitizeReason("\u0000\u0001"))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID));
    }

    /** Missing and whitespace-only reasons fail before any audit insert. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t", "\u0000\u0001"})
    void rejectsInvalidReasonBeforePersistence(String reason) {
        assertThatThrownBy(() -> service.record(11L, 7L, 8L, null,
                "ROLE_CHANGED", "user", "admin", reason))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID));

        verifyNoInteractions(auditMapper);
    }

    /** Recognized inline assignments and valid JSON keep non-secret operational context when safe. */
    @ParameterizedTest
    @ValueSource(strings = {
            "approved PASSWORD=secret-marker",
            "approved passwd:'secret-marker with spaces'",
            "approved token : secret-marker",
            "approved secret=secret-marker",
            "approved authorization=secret-marker",
            "approved credential:secret-marker",
            "approved code=secret-marker",
            "approved otp='secret-marker'",
            "{\"reason\":\"approved\",\"token\":\"secret-marker\"}"
    })
    void masksEachSecretAssignment(String reason) {
        assertThat(service.sanitizeReason(reason))
                .contains("approved")
                .contains("******")
                .doesNotContain("secret-marker")
                .doesNotContain("with spaces");
    }

    /** Unstructured text with quoted credentials is conservatively redacted as a whole value. */
    @Test
    void fullyRedactsUnstructuredQuotedCredentials() {
        assertThat(service.sanitizeReason("approved pwd=\"secret-marker with spaces\""))
                .isEqualTo("******");
    }

    /** Reasons keep readable text, collapse controls and respect the database's 255-character limit. */
    @Test
    void normalizesAndBoundsReasonAfterMasking() {
        assertThat(service.sanitizeReason("  approved\u0000\n\t by   owner  "))
                .isEqualTo("approved by owner");
        assertThat(service.sanitizeReason("a".repeat(255))).isEqualTo("a".repeat(255));
        assertThat(service.sanitizeReason("a".repeat(256))).isEqualTo("a".repeat(255));
    }

    /** Secret assignment values are removed before truncation can leave a partial credential. */
    @Test
    void masksLongSecretBeforeReasonTruncation() {
        String prefix = "a".repeat(240);

        String reason = service.sanitizeReason(prefix + " token=secret-marker-" + "z".repeat(100));

        assertThat(reason).hasSizeLessThanOrEqualTo(255)
                .startsWith(prefix)
                .doesNotContain("secret-marker")
                .doesNotContain("zzzz");
    }

    /** Stored events preserve tenant, actor, target and timestamp while sanitizing evidence. */
    @Test
    void persistsSanitizedMemberAuditWithCompleteIdentity() {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        service.record(11L, 7L, 8L, null, "ROLE_CHANGED",
                "  us\u0000er\n", " admin\t", " approved\n token=secret-marker ");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getId()).isEqualTo(901L);
        assertThat(event.getTenantId()).isEqualTo(11L);
        assertThat(event.getActorId()).isEqualTo(7L);
        assertThat(event.getTargetAccountId()).isEqualTo(8L);
        assertThat(event.getInvitationId()).isNull();
        assertThat(event.getAction()).isEqualTo("ROLE_CHANGED");
        assertThat(event.getOldValue()).isEqualTo("us er");
        assertThat(event.getNewValue()).isEqualTo("admin");
        assertThat(event.getReason()).isEqualTo("approved token=******");
        assertThat(event.getCreateTime()).isBetween(before, LocalDateTime.now());
    }

    /** Invitation events support absent account/value fields without inventing placeholder strings. */
    @Test
    void preservesNullValuesForInvitationAudit() {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);

        service.record(0L, 7L, null, 101L, "INVITATION_CREATED", null, null, "approved");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getTenantId()).isZero();
        assertThat(event.getTargetAccountId()).isNull();
        assertThat(event.getInvitationId()).isEqualTo(101L);
        assertThat(event.getOldValue()).isNull();
        assertThat(event.getNewValue()).isNull();
    }

    /** Both old and new state values are bounded to the schema after control-character removal. */
    @Test
    void boundsOldAndNewValuesToTheirColumnLimit() {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);

        service.record(11L, 7L, 8L, null, "STATUS_CHANGED",
                "x".repeat(65), "y".repeat(64), "approved");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getOldValue()).isEqualTo("x".repeat(64));
        assertThat(event.getNewValue()).isEqualTo("y".repeat(64));
    }

    /** Zero or multiple inserted rows cannot satisfy the mandatory audit-persistence contract. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void failsClosedWhenAuditInsertDoesNotAffectExactlyOneRow(int affectedRows) {
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(affectedRows);

        assertThatThrownBy(() -> service.record(11L, 7L, 8L, null,
                "ROLE_CHANGED", "user", "admin", "approved token=secret-marker"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant member audit persistence failed")
                .hasMessageNotContaining("secret-marker");
    }

    /** Dedicated platform commands suppress duplicate member and invitation rows only inside forced target isolation. */
    @ParameterizedTest
    @MethodSource("platformTargetContexts")
    void suppressesDuplicatePlatformAuditInsideIsolatedTarget(Long tenantId, boolean previousIgnoreIsolation) {
        setPlatformContext();
        TenantContext.setTenantId(0L);
        TenantContext.setIgnoreIsolation(previousIgnoreIsolation);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        TenantContext.runWithTenantIsolation(tenantId, () -> {
            service.record(tenantId, 7L, 8L, null,
                    "ROLE_CHANGED", "user", "admin", "approved token=secret-marker");
            service.record(tenantId, 7L, null, 101L,
                    "INVITATION_CREATED", null, "admin", "approved");
            assertThat(TenantContext.getTenantId()).isEqualTo(tenantId);
            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        });

        verifyNoInteractions(auditMapper);
        ids.verifyNoInteractions();
        assertThat(TenantContext.getTenantId()).isZero();
        assertThat(TenantContext.isIgnoreIsolation()).isEqualTo(previousIgnoreIsolation);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        assertThat(MDC.get(Const.ATTR_USER_ID)).isEqualTo("7");
    }

    /** Platform audit ownership never exempts a command from mandatory reason validation. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t", "\u0000\u0001"})
    void rejectsInvalidReasonBeforePlatformAuditSuppression(String reason) {
        setPlatformContext();

        assertThatThrownBy(() -> service.record(11L, 7L, 8L, null,
                "ROLE_CHANGED", "user", "admin", reason))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID));

        verifyNoInteractions(auditMapper);
        ids.verifyNoInteractions();
    }

    /** Missing or inconsistent actor and tenant facts cannot silently discard ordinary tenant audit evidence. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPlatformCommandContexts")
    void recordsAuditWhenActorOrTenantContextDoesNotMatch(String scenario, Long actorId, String mdcActorId,
                                                         Long targetTenantId, Long contextTenantId,
                                                         boolean ignoreIsolation) {
        setPlatformContext();
        if (mdcActorId == null) {
            MDC.remove(Const.ATTR_USER_ID);
        } else {
            MDC.put(Const.ATTR_USER_ID, mdcActorId);
        }
        TenantContext.clear();
        TenantContext.setTenantId(contextTenantId);
        TenantContext.setIgnoreIsolation(ignoreIsolation);
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);

        service.record(targetTenantId, actorId, 8L, null,
                "ROLE_CHANGED", "user", "admin", "approved token=secret-marker");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getTenantId()).as(scenario).isEqualTo(targetTenantId);
        assertThat(event.getActorId()).isEqualTo(actorId);
        assertThat(event.getReason()).isEqualTo("approved token=******");
        assertThat(TenantContext.getTenantId()).isEqualTo(contextTenantId);
        assertThat(TenantContext.isIgnoreIsolation()).isEqualTo(ignoreIsolation);
    }

    /** Role labels, stale identities and non-platform permissions never substitute for the complete principal contract. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPlatformAuthentications")
    void recordsAuditWhenPrincipalLacksPlatformUserWrite(String scenario, Authentication authentication) {
        setPlatformContext();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(auditMapper.insert(any(AccountMemberAudit.class))).thenReturn(1);

        service.record(11L, 7L, 8L, null, "ROLE_CHANGED", "user", "admin", "approved");

        AccountMemberAudit event = capturedAudit();
        assertThat(event.getTenantId()).as(scenario).isEqualTo(11L);
        assertThat(event.getActorId()).isEqualTo(7L);
        assertThat(event.getAction()).isEqualTo("ROLE_CHANGED");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    /** Covers explicit legacy tenant zero and a business tenant with either prior isolation state. */
    private static Stream<Arguments> platformTargetContexts() {
        return Stream.of(Arguments.of(0L, false), Arguments.of(0L, true),
                Arguments.of(11L, false), Arguments.of(11L, true));
    }

    /** Varies each actor and isolation prerequisite while preserving all other platform principal facts. */
    private static Stream<Arguments> nonPlatformCommandContexts() {
        return Stream.of(
                Arguments.of("missing actor", null, "7", 11L, 11L, false),
                Arguments.of("zero actor", 0L, "0", 11L, 11L, false),
                Arguments.of("negative actor", -1L, "-1", 11L, 11L, false),
                Arguments.of("different MDC actor", 7L, "8", 11L, 11L, false),
                Arguments.of("missing MDC actor", 7L, null, 11L, 11L, false),
                Arguments.of("empty MDC actor", 7L, "", 11L, 11L, false),
                Arguments.of("malformed MDC actor", 7L, "not-an-id", 11L, 11L, false),
                Arguments.of("zero MDC actor", 7L, "0", 11L, 11L, false),
                Arguments.of("missing target tenant", 7L, "7", null, 11L, false),
                Arguments.of("missing tenant context", 7L, "7", 11L, null, false),
                Arguments.of("both tenant facts missing", 7L, "7", null, null, false),
                Arguments.of("system context before target switch", 7L, "7", 11L, 0L, false),
                Arguments.of("different business tenant", 7L, "7", 11L, 12L, false),
                Arguments.of("target isolation bypassed", 7L, "7", 11L, 11L, true));
    }

    /** Exercises authenticated state, principal shape, exact role cardinality and the exact write capability. */
    private static Stream<Arguments> nonPlatformAuthentications() {
        Authentication platform = authentication(true, "ROLE_platform_admin", PlatformPermissions.USER_WRITE);
        return Stream.of(
                Arguments.of("no authentication", null),
                Arguments.of("unauthenticated platform principal",
                        authentication(false, "ROLE_platform_admin", PlatformPermissions.USER_WRITE)),
                Arguments.of("unsupported principal shape", UsernamePasswordAuthenticationToken.authenticated(
                        "platform-operator", null, platform.getAuthorities())),
                Arguments.of("permission without role", authentication(true, PlatformPermissions.USER_WRITE)),
                Arguments.of("platform role without permission", authentication(true, "ROLE_platform_admin")),
                Arguments.of("platform read capability only",
                        authentication(true, "ROLE_platform_admin", PlatformPermissions.USER_READ)),
                Arguments.of("tenant admin", authentication(true, "ROLE_admin", PlatformPermissions.USER_WRITE)),
                Arguments.of("tenant monitor", authentication(true, "ROLE_monitor", PlatformPermissions.USER_WRITE)),
                Arguments.of("tenant user", authentication(true, "ROLE_user", PlatformPermissions.USER_WRITE)),
                Arguments.of("multiple roles", authentication(true, "ROLE_platform_admin", "ROLE_admin",
                        PlatformPermissions.USER_WRITE)),
                Arguments.of("wrong role case", authentication(true, "ROLE_PLATFORM_ADMIN", PlatformPermissions.USER_WRITE)),
                Arguments.of("wildcard capability", authentication(true, "ROLE_platform_admin", "platform:*")));
    }

    /** Creates a real Spring principal and token so the production security predicate remains under test. */
    private static Authentication authentication(boolean authenticated, String... authorities) {
        User principal = (User) User.withUsername("platform-operator").password("unused")
                .authorities(authorities).build();
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        if (!authenticated) {
            token.setAuthenticated(false);
        }
        return token;
    }

    /** Establishes the full platform actor facts inside one explicit target tenant for focused suppression checks. */
    private void setPlatformContext() {
        SecurityContextHolder.getContext().setAuthentication(
                authentication(true, "ROLE_platform_admin", PlatformPermissions.USER_WRITE));
        MDC.put(Const.ATTR_USER_ID, "7");
        MDC.put(Const.ATTR_USER_ROLE, "platform_admin");
        TenantContext.setTenantId(11L);
        TenantContext.setIgnoreIsolation(false);
    }

    /** Returns the actual record delivered to persistence for public-behavior assertions. */
    private AccountMemberAudit capturedAudit() {
        ArgumentCaptor<AccountMemberAudit> event = ArgumentCaptor.forClass(AccountMemberAudit.class);
        verify(auditMapper).insert(event.capture());
        return event.getValue();
    }
}
