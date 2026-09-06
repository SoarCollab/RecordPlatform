package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.platform.PlatformConfigurationEntry;
import cn.flying.dao.mapper.platform.PlatformConfigurationMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.vo.audit.AuditConfigVO;
import cn.flying.dao.vo.platform.PlatformConfigurationVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformSafeLongSerializer;
import cn.flying.dao.vo.platform.UpdatePlatformConfigurationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Code-owned numeric configuration registry and its optimistic mutation boundary. */
@Service
@RequiredArgsConstructor
public class PlatformConfigurationService {

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("HIGH_FREQ_THRESHOLD", 1, 1_000_000, "Operations per five-minute audit window"),
            new Definition("FAILED_LOGIN_THRESHOLD", 1, 10_000, "Failed logins per hour"),
            new Definition("ERROR_RATE_THRESHOLD", 1, 100, "Error-rate alert percentage"),
            new Definition("LOG_RETENTION_DAYS", 1, 3650, "Audit retention in days"));
    private final PlatformConfigurationMapper mapper;
    private final PlatformTenantMapper tenantMapper;
    private final PlatformOperationExecutor executor;

    /** Returns safe global metadata and validated values to platform operators only. */
    public List<PlatformConfigurationVO> list() {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.CONFIGURATION_READ);
        return safeRegistry();
    }

    /** Returns one code-owned entry without querying an arbitrary caller-selected key. */
    public PlatformConfigurationVO get(String key) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.CONFIGURATION_READ);
        Definition definition = requireDefinition(key);
        return toView(definition, mapper.selectEntry(key));
    }

    /** Projects only validated registry values for the intentionally retained tenant audit read endpoint. */
    public List<AuditConfigVO> getSafeTenantAuditConfigs() {
        return safeRegistry().stream().filter(entry -> "AVAILABLE".equals(entry.state())).map(entry -> {
            AuditConfigVO view = new AuditConfigVO();
            view.setConfigKey(entry.key());
            view.setConfigValue(String.valueOf(entry.value()));
            view.setDescription(entry.description());
            return view;
        }).toList();
    }

    /** Updates an allowlisted integer with current-version checks and dedicated durable audit. */
    public PlatformMutationVO update(String key, String idempotencyKey, UpdatePlatformConfigurationRequest request) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.CONFIGURATION_WRITE);
        Definition definition = requireDefinition(key);
        if (request == null || request.value() == null
                || request.value() < definition.minimum() || request.value() > definition.maximum()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return executor.execute(PlatformOperationType.CONFIGURATION_UPDATE, null, null, key,
                idempotencyKey, request, request.reason(), () -> {
                    if (!Objects.equals(tenantMapper.lockSystemConfiguration(), 0L)) {
                        throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
                    }
                    PlatformConfigurationEntry current = mapper.lockEntry(key);
                    Long version = current == null ? 0L : current.getVersion();
                    PlatformInputs.version(version, request.expectedVersion());
                    PlatformConfigurationVO before = toView(definition, current);
                    int changed = current == null
                            ? mapper.insertEntry(key, String.valueOf(request.value()), definition.description())
                            : mapper.updateEntry(key, String.valueOf(request.value()), request.expectedVersion());
                    if (changed != 1) {
                        throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
                    }
                    return new PlatformChange(null, null, key, version + 1,
                            "value=" + (before.value() == null ? "unavailable" : before.value()) + "; version=" + version,
                            "value=" + request.value() + "; version=" + (version + 1));
                });
    }

    /** Resolves the same fixed registry for command validation and audit resource identity. */
    static Definition requireDefinition(String key) {
        return DEFINITIONS.stream().filter(entry -> entry.key().equals(key)).findFirst()
                .orElseThrow(() -> new GeneralException(ResultEnum.PLATFORM_CONFIGURATION_UNSUPPORTED));
    }

    /** Loads a fixed-size set, preserving code-owned ordering and unavailable entries. */
    private List<PlatformConfigurationVO> safeRegistry() {
        Map<String, PlatformConfigurationEntry> rows = mapper.selectSafeEntries().stream()
                .collect(Collectors.toMap(PlatformConfigurationEntry::getConfigKey, Function.identity()));
        return DEFINITIONS.stream().map(definition -> toView(definition, rows.get(definition.key()))).toList();
    }

    /** Drops malformed persisted values while keeping safe metadata available for operator correction. */
    private PlatformConfigurationVO toView(Definition definition, PlatformConfigurationEntry row) {
        Long version = null;
        if (row == null) {
            version = 0L;
        } else if (PlatformSafeLongSerializer.isSafe(row.getVersion())) {
            version = row.getVersion();
        }
        Long value = null;
        if (row != null && version != null && version >= 0) {
            value = parseValue(definition, row.getConfigValue());
        }
        return new PlatformConfigurationVO(definition.key(), "INTEGER", value, version,
                definition.minimum(), definition.maximum(), definition.description(), "GLOBAL", "DATABASE",
                true, false, value == null ? "UNAVAILABLE" : "AVAILABLE");
    }

    /** Accepts canonical bounded integer text only; arbitrary legacy text is never returned. */
    private Long parseValue(Definition definition, String raw) {
        if (raw == null || raw.length() > 16 || !raw.matches("[1-9][0-9]*")) {
            return null;
        }
        try {
            long value = Long.parseLong(raw);
            return value >= definition.minimum() && value <= definition.maximum() ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Keeps key ownership, bounds, and human-facing metadata in reviewed application code. */
    record Definition(String key, long minimum, long maximum, String description) {
    }
}
