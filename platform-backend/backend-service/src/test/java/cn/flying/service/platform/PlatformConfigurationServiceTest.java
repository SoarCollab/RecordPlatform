package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.dao.entity.platform.PlatformConfigurationEntry;
import cn.flying.dao.mapper.platform.PlatformConfigurationMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.vo.platform.PlatformConfigurationVO;
import cn.flying.dao.vo.platform.UpdatePlatformConfigurationRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Covers code-owned registry policy and real executor integration without claiming SQL proof from mapper substitutes. */
class PlatformConfigurationServiceTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";
    private PlatformServiceTestSupport fixture;
    private PlatformConfigurationMapper mapper;
    private PlatformTenantMapper tenants;
    private PlatformConfigurationService service;
    private Map<String, PlatformConfigurationEntry> rows;

    /** Uses the production guard, registry, validator, sanitizer and operation executor. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        mapper = mock(PlatformConfigurationMapper.class);
        tenants = mock(PlatformTenantMapper.class);
        rows = new LinkedHashMap<>();
        for (String key : List.of("HIGH_FREQ_THRESHOLD", "FAILED_LOGIN_THRESHOLD", "ERROR_RATE_THRESHOLD", "LOG_RETENTION_DAYS")) {
            rows.put(key, entry(key, "10", 0L));
        }
        when(mapper.selectSafeEntries()).thenAnswer(invocation -> List.copyOf(rows.values()));
        when(mapper.selectEntry(anyString())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        when(mapper.lockEntry(anyString())).thenAnswer(invocation -> {
            isolated(0);
            return rows.get(invocation.getArgument(0));
        });
        when(tenants.lockSystemConfiguration()).thenReturn(0L);
        when(mapper.updateEntry(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            isolated(0);
            PlatformConfigurationEntry row = rows.get(invocation.getArgument(0));
            row.setConfigValue(invocation.getArgument(1)).setVersion(row.getVersion() + 1);
            return 1;
        });
        when(mapper.insertEntry(anyLong(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            isolated(0);
            String key = invocation.getArgument(1);
            rows.put(key, entry(key, invocation.getArgument(2), 1L));
            return 1;
        });
        service = new PlatformConfigurationService(mapper, tenants, fixture.executor);
    }

    /** Clears shared identity state and resources between tests. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Registry order, bounds and descriptions come from code; unknown persisted keys never become visible. */
    @Test
    void returnsOnlyCodeOwnedRegistryMetadata() throws Exception {
        rows.put("JWT_SECRET", entry("JWT_SECRET", "registry-secret-sentinel", 0L));

        List<PlatformConfigurationVO> views = service.list();

        assertThat(views).extracting(PlatformConfigurationVO::key).containsExactly(
                "HIGH_FREQ_THRESHOLD", "FAILED_LOGIN_THRESHOLD", "ERROR_RATE_THRESHOLD", "LOG_RETENTION_DAYS");
        assertThat(views).allSatisfy(view -> {
            assertThat(view.type()).isEqualTo("INTEGER");
            assertThat(view.scope()).isEqualTo("GLOBAL");
            assertThat(view.source()).isEqualTo("DATABASE");
            assertThat(view.mutable()).isTrue();
            assertThat(view.restartRequired()).isFalse();
            assertThat(view.state()).isEqualTo("AVAILABLE");
        });
        assertThat(fixture.json.writeValueAsString(views)).doesNotContain("registry-secret-sentinel", "JWT_SECRET");
    }

    /** Invalid stored text is marked unavailable while its safe version and correction bounds remain visible. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "01", " 10", "10 ", "false", "1.5", "1000001", "9999999999999999999", "token=config-secret"})
    void suppressesCorruptPersistedValues(String raw) throws Exception {
        rows.put("HIGH_FREQ_THRESHOLD", entry("HIGH_FREQ_THRESHOLD", raw, 7L));

        PlatformConfigurationVO view = service.get("HIGH_FREQ_THRESHOLD");

        assertThat(view.value()).isNull();
        assertThat(view.version()).isEqualTo(7L);
        assertThat(view.state()).isEqualTo("UNAVAILABLE");
        assertThat(fixture.json.writeValueAsString(view)).doesNotContain("config-secret");
    }

    /** Safe tenant audit projection is deliberately available without platform authority and omits corrupt entries. */
    @Test
    void retainsSafeTenantReadProjectionWithoutGlobalWriteAuthority() throws Exception {
        fixture.authorize("admin", 42L);
        rows.put("HIGH_FREQ_THRESHOLD", entry("HIGH_FREQ_THRESHOLD", "token=tenant-secret", 5L));

        var views = service.getSafeTenantAuditConfigs();

        assertThat(views).hasSize(3).allSatisfy(view -> {
            assertThat(view.getId()).isNull();
            assertThat(view.getConfigValue()).isEqualTo("10");
            assertThat(view.getDescription()).isNotBlank();
        });
        assertThat(fixture.json.writeValueAsString(views)).doesNotContain("tenant-secret", "HIGH_FREQ_THRESHOLD");
        isolated(42);
    }

    /** Unknown and secret-bearing keys fail before a configuration mapper is queried. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"JWT_SECRET", "SENSITIVE_MODULES", "AUDIT_ENABLED", "high_freq_threshold"})
    void rejectsUnsupportedKeys(String key) {
        rejected(() -> service.get(key), ResultEnum.PLATFORM_CONFIGURATION_UNSUPPORTED);
        rejected(() -> service.update(key, KEY, update(10L, 0L)), ResultEnum.PLATFORM_CONFIGURATION_UNSUPPORTED);
        verifyNoInteractions(mapper, tenants, fixture.operations);
    }

    /** Inclusive registry bounds are writable through optimistic updates and one durable success outcome. */
    @ParameterizedTest
    @CsvSource({"HIGH_FREQ_THRESHOLD,1", "HIGH_FREQ_THRESHOLD,1000000", "FAILED_LOGIN_THRESHOLD,10000",
            "ERROR_RATE_THRESHOLD,100", "LOG_RETENTION_DAYS,3650"})
    void acceptsRegistryBoundsAndReturnsRecordedReplay(String key, long value) {
        var request = update(value, 0L);
        var result = service.update(key, KEY, request);

        assertThat(result.resourceId()).isEqualTo(key);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(service.get(key).value()).isEqualTo(value);
        assertThat(service.update(key, KEY, request)).isEqualTo(result);
        verify(mapper).updateEntry(key, String.valueOf(value), 0L);
        assertThat(fixture.row().getStatus()).isEqualTo("SUCCESS");
    }

    /** Out-of-bounds integer input is rejected before a command can claim an idempotency key. */
    @ParameterizedTest
    @CsvSource({"HIGH_FREQ_THRESHOLD,0", "HIGH_FREQ_THRESHOLD,1000001", "FAILED_LOGIN_THRESHOLD,10001",
            "ERROR_RATE_THRESHOLD,101", "LOG_RETENTION_DAYS,3651"})
    void rejectsOutsideRegistryBounds(String key, long value) {
        rejected(() -> service.update(key, KEY, update(value, 0L)), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(mapper, tenants, fixture.operations);
    }

    /** Missing entries receive a distinct positive ID and version one; replay does not insert another row. */
    @Test
    void restoresMissingRegistryEntryWithVersionOne() {
        rows.remove("ERROR_RATE_THRESHOLD");
        assertThat(service.get("ERROR_RATE_THRESHOLD").state()).isEqualTo("UNAVAILABLE");
        assertThat(service.get("ERROR_RATE_THRESHOLD").version()).isZero();

        var request = update(25L, 0L);
        var result = service.update("ERROR_RATE_THRESHOLD", KEY, request);

        assertThat(result.resourceId()).isEqualTo("ERROR_RATE_THRESHOLD");
        assertThat(result.version()).isEqualTo(1L);
        assertThat(rows.get("ERROR_RATE_THRESHOLD").getConfigValue()).isEqualTo("25");
        assertThat(rows.get("ERROR_RATE_THRESHOLD").getVersion()).isEqualTo(1L);
        assertThat(service.update("ERROR_RATE_THRESHOLD", KEY, request)).isEqualTo(result);
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        verify(mapper).insertEntry(id.capture(), eq("ERROR_RATE_THRESHOLD"), eq("25"), eq("Error-rate alert percentage"));
        assertThat(id.getValue()).isPositive().isNotEqualTo(fixture.row().getId());
        verify(mapper, never()).updateEntry(anyString(), anyString(), anyLong());
    }

    /** Version conflicts produce a dedicated failure record without updating the global value. */
    @Test
    void recordsStaleVersionAsFailure() {
        rows.get("HIGH_FREQ_THRESHOLD").setVersion(3L);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 2L)),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        verify(mapper, never()).updateEntry(anyString(), anyString(), anyLong());
        assertThat(fixture.row().getErrorCode()).isEqualTo(ResultEnum.PLATFORM_VERSION_CONFLICT.getCode());
    }

    /** Invalid persisted versions remain unavailable and cannot produce a lossy response or optimistic update. */
    @ParameterizedTest
    @ValueSource(longs = {-1L, 9007199254740992L, Long.MAX_VALUE})
    void rejectsUnsafeStoredVersions(long version) {
        rows.get("HIGH_FREQ_THRESHOLD").setVersion(version);
        var view = service.get("HIGH_FREQ_THRESHOLD");
        assertThat(view.version()).isNull();
        assertThat(view.value()).isNull();
        assertThat(view.state()).isEqualTo("UNAVAILABLE");
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 0L)),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        verify(mapper, never()).updateEntry(anyString(), anyString(), anyLong());
    }

    /** A readable maximum-safe version cannot be incremented into a lossy result. */
    @Test
    void rejectsExhaustedSafeVersionBeforeConfigurationWrite() {
        rows.get("HIGH_FREQ_THRESHOLD").setVersion(9007199254740991L);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 9007199254740991L)),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        verify(mapper, never()).updateEntry(anyString(), anyString(), anyLong());
    }

    /** Correcting a corrupt value never copies that value into before-state audit evidence. */
    @Test
    void correctsCorruptValueWithoutLeakingItIntoAudit() throws Exception {
        rows.get("ERROR_RATE_THRESHOLD").setConfigValue("token=old-config-secret").setVersion(8L);

        var result = service.update("ERROR_RATE_THRESHOLD", KEY, update(50L, 8L));

        assertThat(result.version()).isEqualTo(9L);
        assertThat(fixture.row().getBeforeSummary()).contains("value=unavailable");
        assertThat(fixture.json.writeValueAsString(fixture.row())).doesNotContain("old-config-secret");
    }

    /** A lost conditional write is a version conflict, never a successful recorded configuration mutation. */
    @Test
    void rejectsUnexpectedUpdateCount() {
        doReturn(0).when(mapper).updateEntry(anyString(), anyString(), anyLong());
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 0L)),
                ResultEnum.PLATFORM_VERSION_CONFLICT);
        assertThat(fixture.row().getStatus()).isEqualTo("FAILURE");
    }

    /** Missing system metadata cannot authorize a global configuration mutation. */
    @Test
    void requiresSystemConfigurationFence() {
        when(tenants.lockSystemConfiguration()).thenReturn(null);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 0L)), ResultEnum.SERVICE_UNAVAILABLE);
        verify(mapper, never()).lockEntry(anyString());
    }

    /** Tenant-role callers cannot use either platform configuration read or write methods. */
    @Test
    void rejectsTenantActorBeforePersistence() {
        fixture.authorize("monitor", 0L);
        rejected(service::list, ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.get("HIGH_FREQ_THRESHOLD"), ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(20L, 0L)), ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(mapper, tenants, fixture.operations);
    }

    /** Missing versions and values fail without storing a partial command. */
    @Test
    void rejectsInvalidRequestFields() {
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, null), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(null, 0L)), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(10L, null)), ResultEnum.PARAM_IS_INVALID);
        rejected(() -> service.update("HIGH_FREQ_THRESHOLD", KEY, update(10L, 9007199254740992L)), ResultEnum.PARAM_IS_INVALID);
        verifyNoInteractions(mapper, tenants, fixture.operations);
    }

    /** Creates minimal safe registry rows without free-form descriptions. */
    private PlatformConfigurationEntry entry(String key, String value, Long version) {
        return new PlatformConfigurationEntry().setConfigKey(key).setConfigValue(value).setVersion(version);
    }

    /** Builds one ordinary reason-bearing optimistic update. */
    private UpdatePlatformConfigurationRequest update(Long value, Long version) {
        return new UpdatePlatformConfigurationRequest(value, version, "approved password=reason-secret");
    }
}
