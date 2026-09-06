package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** Tests durable command decisions and transaction choreography; the real database suite proves atomicity. */
class PlatformOperationExecutorTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private PlatformServiceTestSupport fixture;

    /** Builds real validation and sanitization around an observable persistence boundary. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
    }

    /** Clears security and tenant state between independent cases. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Claim completion precedes the business callback; success audit and business work share a transaction. */
    @Test
    void commitsClaimThenBusinessAndSanitizedSuccess() {
        CreatePlatformTenantRequest request = request("private-name", "approved token=secret-sentinel");
        PlatformMutationVO result = execute(KEY, request, () -> TenantContext.callWithTenantIsolation(42L, () -> {
            isolated(42);
            fixture.events.add("business");
            return new PlatformChange(42L, 42L, null, 0L, "password=old-sentinel", "status=1");
        }));

        assertThat(result).isEqualTo(new PlatformMutationVO("E1001", "E42", 0L));
        assertThat(fixture.events).containsExactly("begin", "commit", "begin", "claim", "commit", "begin",
                "lock-operation", "business", "success-audit", "commit");
        assertThat(fixture.row().getStatus()).isEqualTo("SUCCESS");
        assertThat(fixture.row().getReason()).doesNotContain("secret-sentinel");
        assertThat(fixture.row().getBeforeSummary()).doesNotContain("old-sentinel");
        assertThat(fixture.row().getResultJson()).doesNotContain("private-name", "secret-sentinel");
        assertThat(fixture.row().getRequestHash()).matches("[0-9a-f]{64}");
        assertThat(fixture.row().getDurationMs()).isNotNegative();
        isolated(0);
    }

    /** A retry returns the original operation and result without invoking the action a second time. */
    @Test
    void returnsOriginalResultForMatchingReplay() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<PlatformChange> action = () -> {
            calls.incrementAndGet();
            return change();
        };
        PlatformMutationVO first = execute(KEY, request("tenant", "approved"), action);
        PlatformMutationVO replay = execute(KEY, request("tenant", "approved"), action);

        assertThat(replay).isEqualTo(first);
        assertThat(calls).hasValue(1);
        assertThat(fixture.rows).hasSize(1);
    }

    /** Different actual payloads conflict even when their redacted diagnostic strings are identical. */
    @Test
    void fingerprintsActualPayloadInsteadOfRedactedToString() {
        CreatePlatformTenantRequest first = request("first", "approved");
        CreatePlatformTenantRequest second = request("second", "approved");
        assertThat(first.toString()).isEqualTo(second.toString());
        execute(KEY, first, this::change);

        rejected(() -> execute(KEY, second, this::change), ResultEnum.PLATFORM_IDEMPOTENCY_CONFLICT);

        assertThat(fixture.row().getStatus()).isEqualTo("SUCCESS");
    }

    /** Equivalent uppercase UUID spellings identify the same original operation. */
    @Test
    void normalizesUppercaseUuidForReplay() {
        String key = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE";
        PlatformMutationVO result = execute(key, request("tenant", "approved"), this::change);
        assertThat(execute(key.toLowerCase(java.util.Locale.ROOT), request("tenant", "approved"), this::change))
                .isEqualTo(result);
    }

    /** A racing initial lookup still relies on the unique constraint and returns the existing outcome. */
    @Test
    void handlesDuplicateClaimAfterRacingInitialLookup() {
        PlatformMutationVO original = execute(KEY, request("tenant", "approved"), this::change);
        fixture.missNextLookup = true;

        assertThat(execute(KEY, request("tenant", "approved"), () -> {
            throw new AssertionError("A unique-key loser must not execute the action");
        })).isEqualTo(original);
        assertThat(fixture.rows).hasSize(1);
    }

    /** A business failure rolls back before its stable category is recorded in a fresh transaction. */
    @Test
    void recordsBusinessFailureAfterRollbackAndReplaysIt() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<PlatformChange> fail = () -> {
            calls.incrementAndGet();
            throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
        };
        rejected(() -> execute(KEY, request("tenant", "approved"), fail), ResultEnum.PLATFORM_VERSION_CONFLICT);

        assertThat(fixture.events).containsExactly("begin", "commit", "begin", "claim", "commit", "begin", "lock-operation",
                "rollback", "begin", "lock-operation", "failure-audit", "commit");
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
        assertThat(fixture.row().getErrorCode()).isEqualTo(ResultEnum.PLATFORM_VERSION_CONFLICT.getCode());
        assertThat(fixture.row().getResultJson()).isNull();
        rejected(() -> execute(KEY, request("tenant", "approved"), fail), ResultEnum.PLATFORM_VERSION_CONFLICT);
        assertThat(calls).hasValue(1);
    }

    /** Unknown exceptions are reduced to a fixed unavailable category without arbitrary diagnostic persistence. */
    @Test
    void neverPersistsRawFailureMessage() throws Exception {
        rejected(() -> execute(KEY, request("tenant", "approved"), () -> {
            throw new IllegalStateException("password=exception-secret-sentinel");
        }), ResultEnum.SERVICE_UNAVAILABLE);

        assertThat(fixture.json.writeValueAsString(fixture.row())).doesNotContain("exception-secret-sentinel");
        assertThat(fixture.row().getErrorCode()).isEqualTo(ResultEnum.SERVICE_UNAVAILABLE.getCode());
    }

    /** A failed claim cannot run any business action or create a false accepted operation. */
    @Test
    void doesNotRunActionWhenClaimPersistenceFails() {
        fixture.failClaim = true;
        AtomicInteger calls = new AtomicInteger();
        rejected(() -> execute(KEY, request("tenant", "approved"), () -> {
            calls.incrementAndGet();
            return change();
        }), ResultEnum.SERVICE_UNAVAILABLE);

        assertThat(calls).hasValue(0);
        assertThat(fixture.rows).isEmpty();
        assertThat(fixture.events).containsExactly("begin", "commit", "begin", "claim", "rollback");
    }

    /** A failed success-audit write forces rollback and a separate failure outcome. */
    @Test
    void doesNotCommitSuccessWhenSuccessAuditFails() {
        fixture.failSuccess = true;
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.SERVICE_UNAVAILABLE);

        assertThat(fixture.events).containsSubsequence("success-audit", "rollback", "begin", "failure-audit", "commit");
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
        assertThat(fixture.row().getResultJson()).isNull();
    }

    /** Audit-store failure retains an ambiguous claim and blocks automatic retry execution. */
    @Test
    void leavesDurableInProgressEvidenceWhenFailureAuditIsUnavailable() {
        fixture.failSuccess = true;
        fixture.failFailure = true;
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.SERVICE_UNAVAILABLE);
        assertThat(fixture.row().getStatus()).isEqualTo("PROCESSING");

        fixture.failSuccess = false;
        fixture.failFailure = false;
        rejected(() -> execute(KEY, request("tenant", "approved"), () -> {
            throw new AssertionError("An ambiguous action must not be repeated");
        }), ResultEnum.PLATFORM_OPERATION_IN_PROGRESS);
        isolated(0);
    }

    /** Missing or short UUIDs fail validation before a durable claim exists. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1-1-1-1-1", "not-a-uuid", "11111111-2222-3333-4444-55555555555Z", " 11111111-2222-3333-4444-555555555555"})
    void rejectsInvalidIdempotencyKeys(String key) {
        rejected(() -> execute(key, request("tenant", "approved"), this::change), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(fixture.operations);
    }

    /** Every command rejects missing and whitespace-only reasons before side effects. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsMissingReasons(String reason) {
        rejected(() -> execute(KEY, request("tenant", reason), this::change), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(fixture.operations);
    }

    /** Service entry validates request annotations even when callers bypass the MVC controller. */
    @Test
    void rejectsOutOfRangeRequestBeforeClaim() {
        UpdatePlatformQuotaRequest request = new UpdatePlatformQuotaRequest(9007199254740992L, 1L, 0L, "approved");
        rejected(() -> fixture.executor.execute(PlatformOperationType.QUOTA_UPDATE, 42L, 42L, null, KEY,
                request, request.reason(), this::change), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> execute(KEY, request("tenant", "x".repeat(256)), this::change), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> fixture.executor.execute(PlatformOperationType.TENANT_CREATE, null, null, null, KEY,
                null, "approved", this::change), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(fixture.operations);
    }

    /** Ordinary tenant administrators cannot reach the operation store, even in tenant zero. */
    @Test
    void deniesNonPlatformActorBeforeClaim() {
        fixture.authorize("admin", 0L);
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(fixture.operations);
    }

    /** A platform principal cannot use the executor with a target context or bypass flag already installed. */
    @Test
    void deniesWrongOrBypassedEntryContext() {
        TenantContext.setTenantId(42L);
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.PERMISSION_UNAUTHORIZED);
        TenantContext.setTenantId(0L);
        TenantContext.setIgnoreIsolation(true);
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(fixture.operations);
    }

    /** Failed target work restores the full system context before failure-audit access. */
    @Test
    void restoresContextWhenTargetCallbackThrows() {
        rejected(() -> execute(KEY, request("tenant", "approved"), () ->
                TenantContext.callWithTenantIsolation(42L, () -> {
                    isolated(42);
                    throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
                })), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        isolated(0);
    }

    /** Arbitrary trace text is omitted while correctly shaped trace identifiers are retained. */
    @ParameterizedTest
    @ValueSource(strings = {"password=trace-secret", "0123456789abcdef0123456789abcdef"})
    void boundsTraceMetadata(String trace) {
        MDC.put("traceId", trace);
        execute(KEY, request("tenant", "approved"), this::change);
        assertThat(fixture.row().getTraceId()).isEqualTo(trace.startsWith("password") ? null : trace);
    }

    /** Corrupt saved result data cannot be exposed as a successful replay. */
    @ParameterizedTest
    @ValueSource(strings = {"malformed", "{\"operationId\":\"secret\",\"resourceId\":\"E42\",\"version\":0}",
            "{\"operationId\":\"E1001\",\"resourceId\":\"E42\",\"version\":-1}",
            "{\"operationId\":\"E1001\",\"resourceId\":\"E42\",\"version\":9007199254740992}"})
    void rejectsCorruptRecordedResults(String json) {
        execute(KEY, request("tenant", "approved"), this::change);
        fixture.row().setResultJson(json);
        rejected(() -> execute(KEY, request("tenant", "approved"), this::change), ResultEnum.SERVICE_UNAVAILABLE);
    }

    /** A missing claimed row is an unavailable audit boundary and never authorizes business work. */
    @Test
    void rejectsMissingClaimBeforeBusinessWork() {
        fixture.hideClaim = true;
        rejected(() -> execute(KEY, request("tenant", "approved"), () -> {
            throw new AssertionError("Missing claim must not authorize work");
        }), ResultEnum.SERVICE_UNAVAILABLE);
        assertThat(fixture.row().getStatus()).isEqualTo("PROCESSING");
    }

    /** Builds a validated command whose full values remain available only for transient hashing. */
    private CreatePlatformTenantRequest request(String name, String reason) {
        return new CreatePlatformTenantRequest("tenant-test", name, null, null, reason);
    }

    /** Runs one standard create command through the real executor. */
    private PlatformMutationVO execute(String key, CreatePlatformTenantRequest request, Supplier<PlatformChange> action) {
        return fixture.executor.execute(PlatformOperationType.TENANT_CREATE, null, null, null,
                key, request, request.reason(), action);
    }

    /** Supplies an allowlisted result without credentials or mutable business entities. */
    private PlatformChange change() {
        return new PlatformChange(42L, 42L, null, 0L, null, "status=1");
    }
}
