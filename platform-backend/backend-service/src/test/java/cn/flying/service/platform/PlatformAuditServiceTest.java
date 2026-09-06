package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies platform-only visibility and safe operation evidence projections. */
class PlatformAuditServiceTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private PlatformServiceTestSupport fixture;
    private PlatformAuditService service;

    /** Reuses the real executor to produce the source evidence being projected. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        service = new PlatformAuditService(fixture.operations, fixture.executor);
    }

    /** Clears scoped identity and ID state. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Audit views use typed IDs, code-owned taxonomy and the same recorded outcome as command replay. */
    @Test
    void exposesSanitizedSuccessfulEvidence() throws Exception {
        createSuccess();
        fixture.row().setTraceId("token=trace-secret").setBeforeSummary("password=legacy-secret");

        var view = service.get(1001L);

        assertThat(view.id()).isEqualTo("E1001");
        assertThat(view.actorId()).isEqualTo("U7");
        assertThat(view.targetTenantId()).isEqualTo("E42");
        assertThat(view.resourceId()).isEqualTo("E42");
        assertThat(view.operation()).isEqualTo("TENANT_CREATE");
        assertThat(view.result().operationId()).isEqualTo(view.id());
        assertThat(view.traceId()).isNull();
        assertThat(fixture.json.writeValueAsString(view)).doesNotContain(
                "trace-secret", "legacy-secret", "requestHash", "idempotencyKey");
    }

    /** History pagination shares the exact tenant/status filter with its count query. */
    @Test
    void returnsBoundedFilteredHistory() {
        createSuccess();
        when(fixture.operations.countPage(42L, "SUCCESS")).thenReturn(21L);
        when(fixture.operations.selectPage(20, 20, 42L, "SUCCESS")).thenReturn(List.of(fixture.row()));

        var page = service.list(2, 20, 42L, "SUCCESS");

        assertThat(page.getTotal()).isEqualTo(21);
        assertThat(page.getCurrent()).isEqualTo(2);
        assertThat(page.getRecords()).hasSize(1);
    }

    /** Failure views expose only stable business codes and omit successful-result JSON. */
    @Test
    void returnsSafeFailureEvidence() {
        CreatePlatformTenantRequest request = new CreatePlatformTenantRequest("tenant-test", "Tenant", null, null, "approved");
        rejected(() -> fixture.executor.execute(PlatformOperationType.TENANT_CREATE, null, null, null, KEY,
                request, request.reason(), () -> {
                    throw new GeneralException(ResultEnum.PLATFORM_TENANT_CODE_EXISTS);
                }), ResultEnum.PLATFORM_TENANT_CODE_EXISTS);

        var view = service.get(1001L);

        assertThat(view.status()).isEqualTo("FAILURE");
        assertThat(view.errorCode()).isEqualTo(ResultEnum.PLATFORM_TENANT_CODE_EXISTS.getCode());
        assertThat(view.result()).isNull();
    }

    /** Unknown or missing operation identifiers yield the same non-disclosing target error. */
    @Test
    void rejectsMissingOperation() {
        rejected(() -> service.get(999L), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        rejected(() -> service.get(null), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
    }

    /** Invalid filters cannot reach the system-owned history mapper. */
    @Test
    void rejectsInvalidHistoryFilters() {
        rejected(() -> service.list(1, 101, null, null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.list(1, 20, null, "RAW_SECRET"), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.list(1, 20, -1L, null), ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        verifyNoInteractions(fixture.operations);
    }

    /** Tenant-zero administrators cannot read platform history through these service methods. */
    @Test
    void rejectsTenantAdministratorBeforeHistoryAccess() {
        fixture.authorize("admin", 0L);
        rejected(() -> service.list(1, 20, null, null), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.get(1001L), ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(fixture.operations);
    }

    /** Corrupt operation taxonomy and wrong system ownership fail closed rather than exposing raw persisted data. */
    @Test
    void rejectsCorruptOperationMetadata() {
        createSuccess();
        fixture.row().setOperation("password=unexpected-secret");
        rejected(() -> service.get(1001L), ResultEnum.SERVICE_UNAVAILABLE);
        fixture.row().setOperation("TENANT_CREATE").setResourceType("UNKNOWN");
        rejected(() -> service.get(1001L), ResultEnum.SERVICE_UNAVAILABLE);
        fixture.row().setResourceType("TENANT").setTenantId(42L);
        rejected(() -> service.get(1001L), ResultEnum.SERVICE_UNAVAILABLE);
    }

    /** Creates one ordinary successful operation without placing request metadata in the output. */
    private void createSuccess() {
        CreatePlatformTenantRequest request = new CreatePlatformTenantRequest("tenant-test", "Tenant", null, null, "approved");
        fixture.executor.execute(PlatformOperationType.TENANT_CREATE, null, null, null, KEY,
                request, request.reason(), () -> new PlatformChange(42L, 42L, null, 0L, null, "status=1"));
    }
}
