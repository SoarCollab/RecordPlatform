package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.platform.PlatformOverviewRow;
import cn.flying.dao.mapper.platform.PlatformOverviewMapper;
import cn.flying.dao.vo.platform.PlatformHealthVO;
import cn.flying.dao.vo.platform.PlatformOverviewVO;
import cn.flying.dao.vo.platform.PlatformSessionVO;
import cn.flying.dao.vo.system.ComponentHealthVO;
import cn.flying.dao.vo.system.SystemHealthVO;
import cn.flying.service.SystemMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform identity, metadata counts and sanitized shared health projections. */
@Service
@RequiredArgsConstructor
public class PlatformQueryService {

    private static final List<String> COMPONENTS = List.of("database", "redis", "blockchain", "storage");
    private static final Set<String> STATUSES = Set.of("UP", "DOWN", "OUT_OF_SERVICE", "DEGRADED", "UNKNOWN");
    private final PlatformOverviewMapper overviewMapper;
    private final SystemMonitorService monitorService;

    /** Exposes the authenticated platform actor and deterministic code-owned capabilities. */
    public PlatformSessionVO session() {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.OVERVIEW_READ);
        return new PlatformSessionVO(IdUtils.toExternalUserId(actorId),
                SecurityContextHolder.getContext().getAuthentication().getName(), "platform", 0L,
                List.copyOf(PlatformPermissions.allCodes()));
    }

    /** Returns only real successfully measured metadata counts; query failures propagate as failed requests. */
    public PlatformOverviewVO overview() {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.OVERVIEW_READ);
        PlatformOverviewRow row = overviewMapper.selectOverview();
        if (row == null) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return new PlatformOverviewVO(PlatformInputs.measured(row.getTenants()),
                PlatformInputs.measured(row.getActiveTenants()), PlatformInputs.measured(row.getDisabledTenants()),
                PlatformInputs.measured(row.getUsers()), PlatformInputs.measured(row.getActiveUsers()), "AVAILABLE");
    }

    /** Adapts existing bounded health checks into a fixed name/status allowlist without arbitrary provider details. */
    public PlatformHealthVO health() {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.RESOURCE_READ);
        SystemHealthVO health;
        try {
            health = TenantContext.callWithTenantIsolation(0L, monitorService::getSystemHealth);
        } catch (RuntimeException unavailable) {
            health = null;
        }
        Map<String, String> components = new LinkedHashMap<>();
        Map<String, ComponentHealthVO> provider = health == null || health.components() == null
                ? Map.of() : health.components();
        for (String name : COMPONENTS) {
            ComponentHealthVO component = provider.get(name);
            components.put(name, normalizeStatus(component == null ? null : component.status()));
        }
        String aggregate = normalizeStatus(health == null ? null : health.status());
        if (components.containsValue("DOWN") || "DOWN".equals(aggregate)) {
            aggregate = "DOWN";
        } else if (components.containsValue("DEGRADED") || "DEGRADED".equals(aggregate)) {
            aggregate = "DEGRADED";
        } else if (components.containsValue("UNKNOWN") || "UNKNOWN".equals(aggregate)) {
            aggregate = "UNKNOWN";
        }
        return new PlatformHealthVO(aggregate, Collections.unmodifiableMap(components));
    }

    /** Converts unsupported and missing status values to UNKNOWN and preserves outage severity. */
    private String normalizeStatus(String value) {
        if (value == null || !STATUSES.contains(value)) {
            return "UNKNOWN";
        }
        return "OUT_OF_SERVICE".equals(value) ? "DOWN" : value;
    }
}
