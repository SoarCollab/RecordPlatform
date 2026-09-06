package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.dao.entity.platform.PlatformOverviewRow;
import cn.flying.dao.mapper.platform.PlatformOverviewMapper;
import cn.flying.dao.vo.system.ComponentHealthVO;
import cn.flying.dao.vo.system.SystemHealthVO;
import cn.flying.service.SystemMonitorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.flying.service.platform.PlatformServiceTestSupport.isolated;
import static cn.flying.service.platform.PlatformServiceTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies platform capabilities, truthful measurements and sanitized shared health. */
class PlatformQueryServiceTest {

    private PlatformServiceTestSupport fixture;
    private PlatformOverviewMapper mapper;
    private SystemMonitorService monitor;
    private PlatformQueryService service;

    /** Composes a real platform guard with bounded aggregate and health provider substitutes. */
    @BeforeEach
    void setUp() {
        fixture = new PlatformServiceTestSupport();
        mapper = mock(PlatformOverviewMapper.class);
        monitor = mock(SystemMonitorService.class);
        service = new PlatformQueryService(mapper, monitor);
    }

    /** Clears security state after each focused contract. */
    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /** Session capabilities are deterministic code-owned strings and the actor retains user-ID type. */
    @Test
    void exposesFixedPlatformSessionCapabilities() {
        var view = service.session();

        assertThat(view.actorId()).isEqualTo("U7");
        assertThat(view.username()).isEqualTo("platform.operator");
        assertThat(view.scope()).isEqualTo("platform");
        assertThat(view.systemTenantId()).isZero();
        assertThat(view.capabilities()).containsExactlyElementsOf(PlatformPermissions.allCodes());
        verifyNoInteractions(mapper, monitor);
    }

    /** Successful empty measurements stay zero without changing their documented scope. */
    @Test
    void returnsRealEmptyAggregateAsAvailable() {
        when(mapper.selectOverview()).thenReturn(new PlatformOverviewRow().setTenants(0L).setActiveTenants(0L)
                .setDisabledTenants(0L).setUsers(0L).setActiveUsers(0L));

        var view = service.overview();

        assertThat(view.tenants()).isZero();
        assertThat(view.users()).isZero();
        assertThat(view.state()).isEqualTo("AVAILABLE");
    }

    /** Missing rows or null count fields produce a failed request instead of a fabricated healthy zero. */
    @Test
    void rejectsUnavailableMeasurements() {
        rejected(service::overview, ResultEnum.SERVICE_UNAVAILABLE);
        when(mapper.selectOverview()).thenReturn(new PlatformOverviewRow().setTenants(1L));
        rejected(service::overview, ResultEnum.SERVICE_UNAVAILABLE);
        when(mapper.selectOverview()).thenReturn(new PlatformOverviewRow().setTenants(-1L));
        rejected(service::overview, ResultEnum.SERVICE_UNAVAILABLE);
        when(mapper.selectOverview()).thenReturn(new PlatformOverviewRow().setTenants(9007199254740992L));
        rejected(service::overview, ResultEnum.SERVICE_UNAVAILABLE);
    }

    /** Health retains only fixed component names and normalized statuses, with severity preserved. */
    @ParameterizedTest
    @CsvSource({"UP,UP", "DOWN,DOWN", "OUT_OF_SERVICE,DOWN", "DEGRADED,DEGRADED", "UNKNOWN,UNKNOWN", "password=secret,UNKNOWN"})
    void sanitizesProviderHealthAndPreservesSeverity(String providerStatus, String expected) throws Exception {
        Map<String, ComponentHealthVO> components = healthyComponents();
        components.put("database", new ComponentHealthVO(providerStatus,
                Map.of("password", "health-secret", "url", "http://infrastructure.example/private")));
        components.put("internal-admin-console", new ComponentHealthVO("UP", Map.of("token", "console-secret")));
        when(monitor.getSystemHealth()).thenAnswer(invocation -> {
            isolated(0);
            return new SystemHealthVO("UP", components, 100L, "timestamp-secret");
        });

        var view = service.health();

        assertThat(view.status()).isEqualTo(expected);
        assertThat(view.components()).containsOnlyKeys("database", "redis", "blockchain", "storage");
        assertThat(fixture.json.writeValueAsString(view)).doesNotContain(
                "health-secret", "console-secret", "infrastructure.example", "timestamp-secret", "password=secret");
        isolated(0);
    }

    /** Missing components and a failed provider remain visibly unknown. */
    @Test
    void reportsUnknownForIncompleteOrUnavailableHealth() {
        when(monitor.getSystemHealth()).thenReturn(new SystemHealthVO("UP", Map.of(), 1L, "now"));
        assertThat(service.health().status()).isEqualTo("UNKNOWN");
        when(monitor.getSystemHealth()).thenThrow(new IllegalStateException("password=provider-secret"));
        assertThat(service.health().status()).isEqualTo("UNKNOWN");
        assertThat(service.health().components()).allSatisfy((name, status) -> assertThat(status).isEqualTo("UNKNOWN"));
    }

    /** A provider's known global degradation is not hidden merely because available component rows are up. */
    @Test
    void preservesProviderAggregateDegradation() {
        when(monitor.getSystemHealth()).thenReturn(new SystemHealthVO("DEGRADED", healthyComponents(), 1L, "now"));
        assertThat(service.health().status()).isEqualTo("DEGRADED");
    }

    /** Ordinary tenant administrators cannot call any platform overview or resource entry point. */
    @Test
    void deniesTenantActorBeforeProviderAccess() {
        fixture.authorize("admin", 0L);
        rejected(service::session, ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(service::overview, ResultEnum.PERMISSION_UNAUTHORIZED);
        rejected(service::health, ResultEnum.PERMISSION_UNAUTHORIZED);
        verifyNoInteractions(mapper, monitor);
    }

    /** Creates the exact allowlisted healthy provider set for projection tests. */
    private Map<String, ComponentHealthVO> healthyComponents() {
        Map<String, ComponentHealthVO> components = new LinkedHashMap<>();
        for (String key : java.util.List.of("database", "redis", "blockchain", "storage")) {
            components.put(key, new ComponentHealthVO("UP", null));
        }
        return components;
    }
}
