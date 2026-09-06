package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.AccountMemberAudit;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

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
        ids = mockStatic(IdUtils.class);
        ids.when(IdUtils::nextEntityId).thenReturn(901L);
        service = new TenantMemberAuditService(auditMapper);
    }

    /** Releases the scoped ID stub after every test, including failing assertions. */
    @AfterEach
    void tearDown() {
        ids.close();
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

    /** Returns the actual record delivered to persistence for public-behavior assertions. */
    private AccountMemberAudit capturedAudit() {
        ArgumentCaptor<AccountMemberAudit> event = ArgumentCaptor.forClass(AccountMemberAudit.class);
        verify(auditMapper).insert(event.capture());
        return event.getValue();
    }
}
