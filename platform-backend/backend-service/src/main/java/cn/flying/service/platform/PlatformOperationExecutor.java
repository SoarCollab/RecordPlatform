package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.platform.PlatformOperationLog;
import cn.flying.dao.mapper.platform.PlatformOperationMapper;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformSafeLongSerializer;
import cn.flying.service.admin.TenantMemberAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.validation.Validator;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Durable idempotency, business-transaction, and sanitized platform-audit boundary. */
@Service
public class PlatformOperationExecutor {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TRACE_PATTERN = Pattern.compile("[0-9a-fA-F]{16,64}");
    private final PlatformOperationMapper mapper;
    private final TenantMemberAuditService sanitizer;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TransactionTemplate independentTransaction;

    /** Creates separate physical transaction boundaries for claim, business work, and failure recording. */
    public PlatformOperationExecutor(PlatformOperationMapper mapper, TenantMemberAuditService sanitizer,
                                     ObjectMapper objectMapper, Validator validator,
                                     PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.validator = validator;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independentTransaction.setTimeout(30);
    }

    /** Accepts exactly one validated logical command and returns its original durable outcome on replay. */
    public PlatformMutationVO execute(
            PlatformOperationType operation, Long targetTenantId, Long targetId, String targetKey,
            String idempotencyKey, Object request, String reason, Supplier<PlatformChange> action) {
        Long actorId = SecurityUtils.requirePlatformPermission(operation.permission());
        validateRequest(request, reason);
        String key = canonicalKey(idempotencyKey);
        String hash = fingerprint(operation, targetTenantId, targetId, targetKey, request, reason);
        PlatformOperationLog candidate = new PlatformOperationLog()
                .setId(IdUtils.nextEntityId()).setTenantId(0L).setActorId(actorId)
                .setIdempotencyKey(key).setRequestHash(hash).setOperation(operation.name())
                .setTargetTenantId(targetTenantId).setResourceType(operation.resourceType())
                .setResourceId(targetId).setResourceKey(targetKey).setReason(sanitizer.sanitizeReason(reason))
                .setStatus("PROCESSING").setTraceId(safeTraceId()).setStartedAt(LocalDateTime.now());
        Claim claim = claim(candidate);
        if (!claim.created()) {
            return replay(claim.row(), hash);
        }
        try {
            return inSystemTransaction(() -> completeBusiness(claim.row().getId(), hash, action));
        } catch (RuntimeException exception) {
            ResultEnum failure = exception instanceof GeneralException general && general.getResultEnum() != null
                    ? general.getResultEnum() : ResultEnum.SERVICE_UNAVAILABLE;
            PlatformOperationLog terminal;
            try {
                terminal = inSystemTransaction(() -> completeFailure(claim.row().getId(), failure));
            } catch (RuntimeException auditFailure) {
                // An unavailable audit store is an unavailable command; never report unaudited success.
                throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
            }
            return replay(terminal, hash);
        }
    }

    /** Validates constraints before a claim is accepted, without using raw constraint messages in errors. */
    private void validateRequest(Object request, String reason) {
        if (request == null || reason == null || reason.isBlank() || reason.length() > 255
                || !validator.validate(request).isEmpty()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
    }

    /** Rejects Java UUID's permissive short forms and normalizes equivalent hexadecimal spelling. */
    private String canonicalKey(String key) {
        if (key == null || !UUID_PATTERN.matcher(key).matches()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return UUID.fromString(key).toString();
    }

    /** Hashes the actual validated payload in stable key order, never a redacted diagnostic string. */
    private String fingerprint(PlatformOperationType operation, Long tenantId, Long targetId, String targetKey,
                               Object request, String reason) {
        TreeMap<String, Object> command = new TreeMap<>();
        command.put("operation", operation.name());
        command.put("targetTenantId", tenantId);
        command.put("targetId", targetId);
        command.put("targetKey", targetKey);
        command.put("reason", reason);
        command.put("payload", objectMapper.convertValue(request, new TypeReference<TreeMap<String, Object>>() {}));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }

    /** Commits the claim independently and observes a competing command only after its claim transaction ends. */
    private Claim claim(PlatformOperationLog candidate) {
        PlatformOperationLog observed = inSystemTransaction(
                () -> mapper.selectByActorAndKey(candidate.getActorId(), candidate.getIdempotencyKey()));
        if (observed != null) {
            return new Claim(observed, false);
        }
        try {
            return inSystemTransaction(() -> {
                if (mapper.insertClaim(candidate) != 1) {
                    throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
                }
                return new Claim(candidate, true);
            });
        } catch (DuplicateKeyException duplicate) {
            PlatformOperationLog existing = inSystemTransaction(
                    () -> mapper.selectByActorAndKey(candidate.getActorId(), candidate.getIdempotencyKey()));
            if (existing == null) {
                throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
            }
            return new Claim(existing, false);
        }
    }

    /** Locks the claim and makes business changes plus sanitized success one atomic database commit. */
    private PlatformMutationVO completeBusiness(Long id, String hash, Supplier<PlatformChange> action) {
        PlatformOperationLog row = requireRow(mapper.selectForUpdate(id));
        requireFingerprint(row, hash);
        if (!"PROCESSING".equals(row.getStatus())) {
            return replay(row, hash);
        }
        PlatformChange change = Objects.requireNonNull(action.get());
        PlatformMutationVO result = new PlatformMutationVO(
                IdUtils.toExternalId(id), externalResource(row.getResourceType(), change.resourceId(), change.resourceKey()),
                change.version());
        row.setTargetTenantId(change.targetTenantId()).setResourceId(change.resourceId())
                .setResourceKey(change.resourceKey()).setBeforeSummary(safeSummary(change.beforeSummary()))
                .setAfterSummary(safeSummary(change.afterSummary())).setResultJson(writeResult(result))
                .setCompletedAt(LocalDateTime.now());
        row.setDurationMs(duration(row)).setStatus("SUCCESS");
        int updated = TenantContext.callWithTenantIsolation(0L, () -> mapper.completeSuccess(row));
        if (updated != 1) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return result;
    }

    /** Records failure only after the business transaction rolled back, preserving any committed terminal result. */
    private PlatformOperationLog completeFailure(Long id, ResultEnum failure) {
        PlatformOperationLog row = requireRow(mapper.selectForUpdate(id));
        if ("PROCESSING".equals(row.getStatus())) {
            row.setCompletedAt(LocalDateTime.now()).setErrorCode(failure.getCode()).setStatus("FAILURE");
            row.setDurationMs(duration(row));
            if (mapper.completeFailure(id, failure.getCode(), row.getCompletedAt(), row.getDurationMs()) != 1) {
                throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
            }
        }
        return row;
    }

    /** Replays only a matching terminal outcome; ambiguous in-progress operations never rerun automatically. */
    private PlatformMutationVO replay(PlatformOperationLog row, String hash) {
        requireFingerprint(row, hash);
        return switch (row.getStatus()) {
            case "SUCCESS" -> readResult(row);
            case "FAILURE" -> throw new GeneralException(row.getErrorCode() == null
                    ? ResultEnum.SERVICE_UNAVAILABLE : ResultEnum.fromCode(row.getErrorCode()));
            case "PROCESSING" -> throw new GeneralException(ResultEnum.PLATFORM_OPERATION_IN_PROGRESS);
            default -> throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        };
    }

    /** Reads only the fixed response shape and rejects corrupted or mismatched stored identifiers. */
    PlatformMutationVO readResult(PlatformOperationLog row) {
        try {
            PlatformMutationVO result = objectMapper.readValue(row.getResultJson(), PlatformMutationVO.class);
            if (result == null || !Objects.equals(result.operationId(), IdUtils.toExternalId(row.getId()))
                    || !Objects.equals(result.resourceId(), externalResource(
                            row.getResourceType(), row.getResourceId(), row.getResourceKey()))
                    || result.version() != null && !PlatformSafeLongSerializer.isSafe(result.version())) {
                throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }

    /** Serializes a fixed response record, never a business entity or request payload. */
    private String writeResult(PlatformMutationVO result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }

    /** Restores system ownership around every operation-table transaction, including failure paths. */
    private <T> T inSystemTransaction(Supplier<T> action) {
        return TenantContext.callWithTenantIsolation(0L, () -> independentTransaction.execute(status -> action.get()));
    }

    /** Rejects a missing durable claim without exposing database diagnostics. */
    private PlatformOperationLog requireRow(PlatformOperationLog row) {
        if (row == null || !Objects.equals(row.getTenantId(), 0L)) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return row;
    }

    /** Binds every replay and transition to the original actual request fingerprint. */
    private void requireFingerprint(PlatformOperationLog row, String hash) {
        if (!Objects.equals(row.getRequestHash(), hash)) {
            throw new GeneralException(ResultEnum.PLATFORM_IDEMPOTENCY_CONFLICT);
        }
    }

    /** Uses the existing secret masker before applying the stricter reason-sized summary bound. */
    String safeSummary(String value) {
        return value == null ? null : sanitizer.sanitizeReason(value);
    }

    /** Preserves user/entity identifier type and code-owned configuration identity in responses. */
    static String externalResource(String type, Long id, String key) {
        if ("CONFIGURATION".equals(type)) {
            return PlatformConfigurationService.requireDefinition(key).key();
        }
        return "USER".equals(type) ? IdUtils.toExternalUserId(id) : IdUtils.toExternalId(id);
    }

    /** Retains only trace-shaped identifiers, never arbitrary MDC text. */
    private String safeTraceId() {
        return safeTraceId(MDC.get("traceId"));
    }

    /** Revalidates trace metadata when persisted operation evidence is projected to the API. */
    static String safeTraceId(String traceId) {
        return traceId != null && TRACE_PATTERN.matcher(traceId).matches() ? traceId : null;
    }

    /** Records a nonnegative elapsed wall-clock duration even if the system clock moves backward. */
    private long duration(PlatformOperationLog row) {
        return PlatformInputs.measured(Math.max(0L, Duration.between(row.getStartedAt(), row.getCompletedAt()).toMillis()));
    }

    /** Indicates whether this caller owns the new durable claim or is observing an earlier command. */
    private record Claim(PlatformOperationLog row, boolean created) {
    }
}
