package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FriendFileShareMapper;
import cn.flying.dao.vo.file.DownloadKeyGrantVO;
import cn.flying.dao.vo.file.DownloadKeyMaterialVO;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 管理不含密钥材料的短期下载 grant，并原子控制一次性消费与同会话重试。
 */
@Service
public class FileKeyGrantService {

    public static final String PROTOCOL_GRANT_V1 = "grant-v1";
    public static final String PROTOCOL_PLAINTEXT_V0 = "plaintext-v0";

    private static final String KEY_PREFIX = "file:key-grant:v1:";
    private static final String STATE_ISSUED = "ISSUED";
    private static final String STATE_CONSUMED = "CONSUMED";
    private static final int REFERENCE_BYTES = 32;
    private static final int MAX_REFERENCE_ATTEMPTS = 3;
    private static final int MIN_SESSION_LENGTH = 16;
    private static final int MAX_SESSION_LENGTH = 128;
    private static final Instant HARD_LEGACY_PLAINTEXT_DEADLINE =
            Instant.parse("2026-10-01T00:00:00Z");

    private static final String ISSUE_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            for i = 2, #ARGV, 2 do
                redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """;

    private static final String CONSUME_SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return -1
            end
            if redis.call('HGET', KEYS[1], 'bindingHash') ~= ARGV[1] then
                return -2
            end
            if redis.call('HGET', KEYS[1], 'sessionHash') ~= ARGV[2]
                    or redis.call('HGET', KEYS[1], 'principalHash') ~= ARGV[3] then
                return -3
            end
            local state = redis.call('HGET', KEYS[1], 'state')
            local now = tonumber(ARGV[4])
            if state == 'ISSUED' then
                redis.call('HSET', KEYS[1], 'state', 'CONSUMED', 'consumedAt', ARGV[4], 'retryCount', '0')
                return 1
            end
            if state ~= 'CONSUMED' then
                return -4
            end
            local consumedAt = tonumber(redis.call('HGET', KEYS[1], 'consumedAt') or '0')
            local retryCount = tonumber(redis.call('HGET', KEYS[1], 'retryCount') or '0')
            local retryWindow = tonumber(ARGV[5])
            local maxRetries = tonumber(ARGV[6])
            if now - consumedAt > retryWindow or retryCount >= maxRetries then
                return -4
            end
            redis.call('HINCRBY', KEYS[1], 'retryCount', 1)
            return 2
            """;

    private final StringRedisTemplate redisTemplate;
    private final FileMapper fileMapper;
    private final FileShareMapper fileShareMapper;
    private final FriendFileShareMapper friendFileShareMapper;
    private final FileKeyEnvelopeService envelopeService;
    private final FileKeyDeliveryProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final DefaultRedisScript<Long> issueScript;
    private final DefaultRedisScript<Long> consumeScript;

    /**
     * 创建生产 grant 服务及预编译 Redis 脚本。
     */
    @Autowired
    public FileKeyGrantService(StringRedisTemplate redisTemplate,
                               FileMapper fileMapper,
                               FileShareMapper fileShareMapper,
                               FriendFileShareMapper friendFileShareMapper,
                               FileKeyEnvelopeService envelopeService,
                               FileKeyDeliveryProperties properties,
                               MeterRegistry meterRegistry) {
        this(redisTemplate, fileMapper, fileShareMapper, friendFileShareMapper,
                envelopeService, properties, meterRegistry,
                Clock.systemUTC(), new SecureRandom());
    }

    /**
     * 创建可注入时钟与随机源的 grant 服务，供确定性测试使用。
     */
    FileKeyGrantService(StringRedisTemplate redisTemplate,
                        FileMapper fileMapper,
                        FileShareMapper fileShareMapper,
                        FriendFileShareMapper friendFileShareMapper,
                        FileKeyEnvelopeService envelopeService,
                        FileKeyDeliveryProperties properties,
                        MeterRegistry meterRegistry,
                        Clock clock,
                        SecureRandom secureRandom) {
        this.redisTemplate = redisTemplate;
        this.fileMapper = fileMapper;
        this.fileShareMapper = fileShareMapper;
        this.friendFileShareMapper = friendFileShareMapper;
        this.envelopeService = envelopeService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.issueScript = script(ISSUE_SCRIPT);
        this.consumeScript = script(CONSUME_SCRIPT);
        validateConfiguration();
    }

    /**
     * 为已授权加密文件创建短期、不含密钥材料的 grant。
     */
    public DownloadKeyGrantVO issue(FileKeyGrantIssueContext context) {
        validateIssueContext(context);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getGrantTtl());
        FileKeyGrantEnvelopeBinding binding = context.envelopeBinding();
        String sessionHash = fingerprint(context.sessionId());
        String principalHash = principalHash(context);
        String bindingHash = bindingHash(
                context, sessionHash, principalHash, now.toEpochMilli(), expiresAt.toEpochMilli());
        Map<String, String> fields = encodeBinding(context, bindingHash, sessionHash, principalHash,
                now, expiresAt);

        try {
            for (int attempt = 0; attempt < MAX_REFERENCE_ATTEMPTS; attempt++) {
                String reference = newReference();
                Long result = executeIssue(redisKey(reference), fields, properties.getGrantTtl());
                if (Long.valueOf(1L).equals(result)) {
                    envelopeService.auditGrantIssue(binding, context.actorId(), context.accessKind().name());
                    metric("issue", context.accessKind(), "success");
                    return new DownloadKeyGrantVO(reference, PROTOCOL_GRANT_V1, expiresAt);
                }
            }
        } catch (GeneralException exception) {
            envelopeService.auditGrantDenial(binding, context.actorId(), "GRANT_ISSUE_UNAVAILABLE");
            metric("issue", context.accessKind(), "unavailable", "GRANT_ISSUE_UNAVAILABLE");
            throw exception;
        }
        envelopeService.auditGrantDenial(binding, context.actorId(), "GRANT_REFERENCE_COLLISION");
        metric("issue", context.accessKind(), "collision", "GRANT_REFERENCE_COLLISION");
        throw unavailable();
    }

    /**
     * 为认证请求消费 grant，并返回仅供即时导入的密钥材料。
     */
    public DownloadKeyMaterialVO consumeAuthenticated(String reference, String sessionId, Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw denied("ACTOR_REQUIRED");
        }
        return consume(reference, sessionId, actorId, null, false);
    }

    /**
     * 为匿名公开分享消费 grant，并绑定规范化客户端身份。
     */
    public DownloadKeyMaterialVO consumePublic(String reference,
                                               String sessionId,
                                               String publicClientIdentity) {
        if (!StringUtils.hasText(publicClientIdentity)) {
            throw denied("CLIENT_REQUIRED");
        }
        return consume(reference, sessionId, null, publicClientIdentity, true);
    }

    /**
     * 在显式且未过期的迁移窗口内直接交付旧协议密钥。
     */
    public String deliverLegacyPlaintext(File file,
                                         FileKeyGrantEnvelopeBinding binding,
                                         Long actorId) {
        if (!isLegacyPlaintextAllowed()) {
            metric("legacy", accessKind(binding), "disabled", "LEGACY_PROTOCOL_DISABLED");
            throw denied("LEGACY_PROTOCOL_DISABLED");
        }
        String initialKey = envelopeService.unwrapGrantBinding(
                        file, binding, actorId, "PLAINTEXT_V0_COMPATIBILITY")
                .orElseThrow(() -> denied("KEY_NOT_AVAILABLE"));
        envelopeService.auditLegacyPlaintextDelivery(binding, actorId);
        metric("legacy", accessKind(binding), "success");
        return initialKey;
    }

    /**
     * 返回 plaintext-v0 是否仍处于显式迁移窗口。
     */
    public boolean isLegacyPlaintextAllowed() {
        Instant notAfter = properties.getLegacyPlaintextNotAfter();
        return properties.isLegacyPlaintextEnabled()
                && notAfter != null
                && clock.instant().isBefore(notAfter);
    }

    /**
     * 执行公共消费流程：读取非秘密绑定、重校验、原子预留，再精确解封。
     */
    private DownloadKeyMaterialVO consume(String reference,
                                          String sessionId,
                                          Long actorId,
                                          String publicClientIdentity,
                                          boolean publicEndpoint) {
        validateReference(reference);
        validateSession(sessionId);
        String redisKey = redisKey(reference);
        Map<String, String> stored = load(redisKey);
        StoredGrant grant = decode(stored);
        FileKeyGrantAccessKind kind = grant.accessKind();
        try {
            validateConsumer(grant, sessionId, actorId, publicClientIdentity, publicEndpoint);
            File file = loadBoundFile(grant, actorId);
            Long reservation = reserve(redisKey, grant, sessionId, actorId, publicClientIdentity);
            if (!Long.valueOf(1L).equals(reservation) && !Long.valueOf(2L).equals(reservation)) {
                throw denied(reservationReason(reservation));
            }
            String initialKey = TenantContext.callWithTenantIsolation(grant.binding().tenantId(), () ->
                    envelopeService.unwrapGrantBinding(file, grant.binding(), actorId,
                                    Long.valueOf(2L).equals(reservation)
                                            ? "DOWNLOAD_GRANT_RETRY" : "DOWNLOAD_GRANT_CONSUME")
                            .orElseThrow(() -> denied("ENVELOPE_NOT_ACTIVE")));
            metric("consume", kind, Long.valueOf(2L).equals(reservation) ? "retry" : "success");
            return new DownloadKeyMaterialVO(initialKey, PROTOCOL_GRANT_V1);
        } catch (GeneralException exception) {
            String reason = stableReason(exception);
            TenantContext.runWithTenantIsolation(grant.binding().tenantId(),
                    () -> envelopeService.auditGrantDenial(grant.binding(), actorId, reason));
            metric("consume", kind, "denied", reason);
            throw exception;
        }
    }

    /**
     * 在绑定租户内重新加载文件，防止删除、版本或跨租户替换后消费。
     */
    private File loadBoundFile(StoredGrant grant, Long actorId) {
        FileKeyGrantEnvelopeBinding binding = grant.binding();
        return TenantContext.callWithTenantIsolation(binding.tenantId(), () -> {
            File file = fileMapper.selectById(binding.fileId());
            if (file == null
                    || !Objects.equals(file.getTenantId(), binding.tenantId())
                    || !Objects.equals(file.getId(), binding.fileId())
                    || !Objects.equals(normalizedFileVersion(file), binding.fileVersion())
                    || !Objects.equals(file.getFileHash(), binding.fileHash())) {
                throw denied("FILE_BINDING_MISMATCH");
            }
            validateCurrentAuthorization(grant, file, actorId);
            return file;
        });
    }

    /**
     * 重新核验可变分享授权，确保取消、过期、类型收紧或接收者变化立即使旧 grant 失效。
     */
    private void validateCurrentAuthorization(StoredGrant grant, File file, Long actorId) {
        FileKeyGrantEnvelopeBinding binding = grant.binding();
        switch (grant.accessKind()) {
            case OWNER -> {
                if (!Objects.equals(file.getUid(), actorId)
                        || !Objects.equals(binding.recipientId(), actorId)) {
                    throw denied("OWNER_AUTHORIZATION_CHANGED");
                }
            }
            case ADMIN -> {
                if (!SecurityUtils.isAdmin()
                        || !Objects.equals(file.getUid(), binding.recipientId())) {
                    throw denied("ADMIN_AUTHORIZATION_CHANGED");
                }
            }
            case AUTHENTICATED_SHARE, PUBLIC_SHARE -> validateCurrentShareAuthorization(grant, file);
            case FRIEND_SHARE -> validateCurrentFriendAuthorization(binding, file, actorId);
        }
    }

    /**
     * 核验分享码授权仍为 ACTIVE、未过期、覆盖当前文件且公开可见性未被收紧。
     */
    private void validateCurrentShareAuthorization(StoredGrant grant, File file) {
        FileKeyGrantEnvelopeBinding binding = grant.binding();
        FileShare share = fileShareMapper.selectById(binding.recipientId());
        boolean supportedType = share != null
                && (Objects.equals(share.getShareType(), 0) || Objects.equals(share.getShareType(), 1));
        boolean publicStillAllowed = grant.accessKind() != FileKeyGrantAccessKind.PUBLIC_SHARE
                || (share != null && Objects.equals(share.getShareType(), 0));
        if (share == null
                || !Objects.equals(share.getId(), binding.recipientId())
                || !Objects.equals(share.getTenantId(), binding.tenantId())
                || !Objects.equals(share.getUserId(), file.getUid())
                || !Objects.equals(share.getStatus(), FileShare.STATUS_ACTIVE)
                || (share.getExpireTime() != null
                    && !share.getExpireTime().toInstant().isAfter(clock.instant()))
                || !supportedType
                || !publicStillAllowed
                || !containsFileHash(share.getFileHashes(), binding.fileHash())) {
            throw denied("SHARE_AUTHORIZATION_CHANGED");
        }
    }

    /**
     * 核验好友分享仍为 ACTIVE、属于原分享者/接收者并覆盖当前文件。
     */
    private void validateCurrentFriendAuthorization(FileKeyGrantEnvelopeBinding binding,
                                                    File file,
                                                    Long actorId) {
        FriendFileShare share = friendFileShareMapper.selectById(binding.recipientId());
        if (share == null
                || !Objects.equals(share.getId(), binding.recipientId())
                || !Objects.equals(share.getTenantId(), binding.tenantId())
                || !Objects.equals(share.getSharerId(), file.getUid())
                || !Objects.equals(share.getFriendId(), actorId)
                || !Objects.equals(share.getStatus(), FriendFileShare.STATUS_ACTIVE)
                || !containsFileHash(share.getFileHashes(), binding.fileHash())) {
            throw denied("FRIEND_AUTHORIZATION_CHANGED");
        }
    }

    /**
     * 严格解析授权文件哈希数组，损坏或非字符串成员均按不包含处理。
     */
    private boolean containsFileHash(String fileHashes, String expectedFileHash) {
        if (!StringUtils.hasText(fileHashes) || !StringUtils.hasText(expectedFileHash)) {
            return false;
        }
        try {
            List<?> values = JsonConverter.parse(fileHashes, List.class);
            return values != null && values.stream()
                    .allMatch(String.class::isInstance)
                    && values.contains(expectedFileHash);
        } catch (GeneralException exception) {
            return false;
        }
    }

    /**
     * 校验当前调用者、端点、会话和租户与已签发绑定一致。
     */
    private void validateConsumer(StoredGrant grant,
                                  String sessionId,
                                  Long actorId,
                                  String publicClientIdentity,
                                  boolean publicEndpoint) {
        if (grant.publicAccess() != publicEndpoint) {
            throw denied("ENDPOINT_MISMATCH");
        }
        if (clock.millis() >= grant.expiresAtMillis()) {
            throw denied("GRANT_EXPIRED");
        }
        String expectedPrincipal = principalHash(
                grant.binding().tenantId(), actorId, publicClientIdentity, sessionId, publicEndpoint);
        if (!MessageDigest.isEqual(grant.sessionHash().getBytes(StandardCharsets.US_ASCII),
                fingerprint(sessionId).getBytes(StandardCharsets.US_ASCII))) {
            throw denied("SESSION_MISMATCH");
        }
        if (!MessageDigest.isEqual(grant.principalHash().getBytes(StandardCharsets.US_ASCII),
                expectedPrincipal.getBytes(StandardCharsets.US_ASCII))) {
            throw denied("PRINCIPAL_MISMATCH");
        }
        if (!publicEndpoint && !Objects.equals(TenantContext.getTenantId(), grant.binding().tenantId())) {
            throw denied("TENANT_MISMATCH");
        }
    }

    /**
     * 使用 Lua 原子执行首次消费或受控同会话重试。
     */
    private Long reserve(String redisKey,
                         StoredGrant grant,
                         String sessionId,
                         Long actorId,
                         String publicClientIdentity) {
        String principal = principalHash(
                grant.binding().tenantId(), actorId, publicClientIdentity, sessionId, grant.publicAccess());
        try {
            return redisTemplate.execute(
                    consumeScript,
                    Collections.singletonList(redisKey),
                    grant.bindingHash(),
                    fingerprint(sessionId),
                    principal,
                    String.valueOf(clock.millis()),
                    String.valueOf(properties.getRetryWindow().toMillis()),
                    String.valueOf(properties.getMaxSameSessionRetries()));
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 原子创建 Redis hash 并设置毫秒 TTL。
     */
    private Long executeIssue(String redisKey, Map<String, String> fields, Duration ttl) {
        List<String> arguments = new ArrayList<>();
        arguments.add(String.valueOf(ttl.toMillis()));
        fields.forEach((key, value) -> {
            arguments.add(key);
            arguments.add(value);
        });
        try {
            return redisTemplate.execute(issueScript, Collections.singletonList(redisKey), arguments.toArray());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 读取 grant hash 并统一处理过期、缺失和 Redis 故障。
     */
    private Map<String, String> load(String redisKey) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(redisKey);
            if (entries == null || entries.isEmpty()) {
                throw denied("GRANT_MISSING_OR_EXPIRED");
            }
            Map<String, String> result = new LinkedHashMap<>();
            entries.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
            return result;
        } catch (GeneralException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    /**
     * 将 grant 绑定编码为 Redis 中的非敏感 hash 字段。
     */
    private Map<String, String> encodeBinding(FileKeyGrantIssueContext context,
                                              String bindingHash,
                                              String sessionHash,
                                              String principalHash,
                                              Instant issuedAt,
                                              Instant expiresAt) {
        FileKeyGrantEnvelopeBinding binding = context.envelopeBinding();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("state", STATE_ISSUED);
        fields.put("bindingHash", bindingHash);
        fields.put("sessionHash", sessionHash);
        fields.put("principalHash", principalHash);
        fields.put("accessKind", context.accessKind().name());
        fields.put("publicAccess", Boolean.toString(context.publicAccess()));
        fields.put("tenantId", value(binding.tenantId()));
        fields.put("fileId", value(binding.fileId()));
        fields.put("fileVersion", value(binding.fileVersion()));
        fields.put("fileHash", binding.fileHash());
        fields.put("recipientType", binding.recipientType());
        fields.put("recipientId", value(binding.recipientId()));
        fields.put("envelopeId", value(binding.envelopeId()));
        fields.put("keyVersion", value(binding.keyVersion()));
        fields.put("algorithmSuite", value(binding.algorithmSuite()));
        fields.put("signatureSuite", value(binding.signatureSuite()));
        fields.put("kemSuite", value(binding.kemSuite()));
        fields.put("proofSuite", value(binding.proofSuite()));
        fields.put("encryptionAlgorithm", value(binding.encryptionAlgorithm()));
        fields.put("kmsProvider", value(binding.kmsProvider()));
        fields.put("providerContractVersion", value(binding.providerContractVersion()));
        fields.put("providerKeyVersion", value(binding.providerKeyVersion()));
        fields.put("legacyPlaintextAtRest", Boolean.toString(binding.legacyPlaintextAtRest()));
        fields.put("issuedAt", String.valueOf(issuedAt.toEpochMilli()));
        fields.put("expiresAt", String.valueOf(expiresAt.toEpochMilli()));
        fields.put("consumedAt", "0");
        fields.put("retryCount", "0");
        return fields;
    }

    /**
     * 从 Redis hash 解码并严格校验 grant 绑定字段。
     */
    private StoredGrant decode(Map<String, String> fields) {
        try {
            FileKeyGrantEnvelopeBinding binding = new FileKeyGrantEnvelopeBinding(
                    nullableLong(fields.get("envelopeId")), requiredLong(fields, "tenantId"),
                    requiredLong(fields, "fileId"), requiredInteger(fields, "fileVersion"),
                    required(fields, "fileHash"), required(fields, "recipientType"),
                    requiredLong(fields, "recipientId"), requiredInteger(fields, "keyVersion"),
                    required(fields, "algorithmSuite"), required(fields, "signatureSuite"),
                    required(fields, "kemSuite"), required(fields, "proofSuite"),
                    required(fields, "encryptionAlgorithm"), required(fields, "kmsProvider"),
                    requiredInteger(fields, "providerContractVersion"), required(fields, "providerKeyVersion"),
                    requiredBoolean(fields, "legacyPlaintextAtRest"));
            FileKeyGrantAccessKind accessKind = FileKeyGrantAccessKind.valueOf(required(fields, "accessKind"));
            boolean publicAccess = requiredBoolean(fields, "publicAccess");
            String storedBindingHash = required(fields, "bindingHash");
            String sessionHash = required(fields, "sessionHash");
            String principalHash = required(fields, "principalHash");
            long issuedAtMillis = Long.parseLong(required(fields, "issuedAt"));
            long expiresAtMillis = Long.parseLong(required(fields, "expiresAt"));
            if (issuedAtMillis <= 0 || expiresAtMillis <= issuedAtMillis
                    || expiresAtMillis - issuedAtMillis > Duration.ofMinutes(5).toMillis()) {
                throw new IllegalArgumentException("invalid lifetime");
            }
            String expectedBindingHash = bindingHash(
                    binding, accessKind, publicAccess, sessionHash, principalHash,
                    issuedAtMillis, expiresAtMillis);
            if (!completeBinding(binding)
                    || !constantTimeEquals(storedBindingHash, expectedBindingHash)
                    || !accessKindMatches(accessKind, binding.recipientType(), publicAccess)) {
                throw new IllegalArgumentException("binding mismatch");
            }
            return new StoredGrant(binding, accessKind, publicAccess, storedBindingHash,
                    sessionHash, principalHash, expiresAtMillis);
        } catch (RuntimeException exception) {
            throw denied("GRANT_STATE_INVALID");
        }
    }

    /**
     * 校验签发上下文完整且会话格式受限。
     */
    private void validateIssueContext(FileKeyGrantIssueContext context) {
        if (context == null || context.file() == null || context.envelopeBinding() == null
                || context.accessKind() == null) {
            throw denied("ISSUE_CONTEXT_INVALID");
        }
        validateSession(context.sessionId());
        FileKeyGrantEnvelopeBinding binding = context.envelopeBinding();
        if (!completeBinding(binding)) {
            throw denied("ENVELOPE_BINDING_INVALID");
        }
        if (!Objects.equals(context.file().getTenantId(), binding.tenantId())
                || !Objects.equals(context.file().getId(), binding.fileId())
                || !Objects.equals(normalizedFileVersion(context.file()), binding.fileVersion())
                || !Objects.equals(context.file().getFileHash(), binding.fileHash())
                || !Objects.equals(TenantContext.getTenantId(), binding.tenantId())) {
            throw denied("FILE_BINDING_MISMATCH");
        }
        if (!accessKindMatches(context.accessKind(), binding.recipientType(), context.publicAccess())) {
            throw denied("ACCESS_KIND_MISMATCH");
        }
        if (context.publicAccess()) {
            if (context.actorId() != null || !StringUtils.hasText(context.publicClientIdentity())) {
                throw denied("PUBLIC_CONTEXT_INVALID");
            }
        } else if (context.actorId() == null || context.actorId() <= 0
                || context.publicClientIdentity() != null) {
            throw denied("AUTHENTICATED_CONTEXT_INVALID");
        }
        if (context.accessKind() == FileKeyGrantAccessKind.OWNER
                && !Objects.equals(binding.recipientId(), context.actorId())) {
            throw denied("OWNER_CONTEXT_INVALID");
        }
        if (context.accessKind() == FileKeyGrantAccessKind.ADMIN && !SecurityUtils.isAdmin()) {
            throw denied("ADMIN_CONTEXT_INVALID");
        }
    }

    /**
     * 校验所有写入 Redis 的不可变信封路由字段均完整且具有可执行语义。
     */
    private boolean completeBinding(FileKeyGrantEnvelopeBinding binding) {
        if (binding == null || binding.tenantId() == null || binding.tenantId() < 0 || binding.fileId() == null
                || binding.fileId() <= 0 || binding.fileVersion() == null || binding.fileVersion() <= 0
                || !StringUtils.hasText(binding.fileHash())
                || !StringUtils.hasText(binding.recipientType())
                || binding.recipientId() == null || binding.recipientId() <= 0
                || binding.keyVersion() == null || binding.keyVersion() < 0
                || !StringUtils.hasText(binding.algorithmSuite())
                || !StringUtils.hasText(binding.signatureSuite())
                || !StringUtils.hasText(binding.kemSuite())
                || !StringUtils.hasText(binding.proofSuite())
                || !StringUtils.hasText(binding.encryptionAlgorithm())
                || !StringUtils.hasText(binding.kmsProvider())
                || binding.providerContractVersion() == null || binding.providerContractVersion() <= 0
                || !StringUtils.hasText(binding.providerKeyVersion())) {
            return false;
        }
        return binding.legacyPlaintextAtRest()
                ? binding.envelopeId() == null
                : binding.envelopeId() != null && binding.envelopeId() > 0 && binding.keyVersion() > 0;
    }

    /**
     * 校验 grant reference 为固定长度 Base64URL 随机值。
     */
    private void validateReference(String reference) {
        if (!StringUtils.hasText(reference) || reference.length() != 43
                || !reference.matches("[A-Za-z0-9_-]{43}")) {
            throw denied("GRANT_REFERENCE_INVALID");
        }
    }

    /**
     * 校验浏览器内存会话标识长度和可打印字符范围。
     */
    private void validateSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)
                || sessionId.length() < MIN_SESSION_LENGTH
                || sessionId.length() > MAX_SESSION_LENGTH
                || !sessionId.matches("[A-Za-z0-9_-]+")) {
            throw denied("SESSION_INVALID");
        }
    }

    /**
     * 计算包含全部不可变上下文的稳定绑定摘要。
     */
    private String bindingHash(FileKeyGrantIssueContext context,
                               String sessionHash,
                               String principalHash,
                               long issuedAtMillis,
                               long expiresAtMillis) {
        return bindingHash(context.envelopeBinding(), context.accessKind(), context.publicAccess(),
                sessionHash, principalHash, issuedAtMillis, expiresAtMillis);
    }

    /**
     * 根据解码后的闭集字段重新计算绑定摘要，防止 Redis 字段被篡改后重定向授权。
     */
    private String bindingHash(FileKeyGrantEnvelopeBinding binding,
                               FileKeyGrantAccessKind accessKind,
                               boolean publicAccess,
                               String sessionHash,
                               String principalHash,
                               long issuedAtMillis,
                               long expiresAtMillis) {
        return fingerprint(String.join("\n",
                PROTOCOL_GRANT_V1,
                accessKind.name(),
                Boolean.toString(publicAccess),
                value(binding.tenantId()),
                value(binding.fileId()),
                value(binding.fileVersion()),
                binding.fileHash(),
                binding.recipientType(),
                value(binding.recipientId()),
                value(binding.envelopeId()),
                value(binding.keyVersion()),
                value(binding.algorithmSuite()),
                value(binding.signatureSuite()),
                value(binding.kemSuite()),
                value(binding.proofSuite()),
                value(binding.encryptionAlgorithm()),
                value(binding.kmsProvider()),
                value(binding.providerContractVersion()),
                value(binding.providerKeyVersion()),
                Boolean.toString(binding.legacyPlaintextAtRest()),
                sessionHash,
                principalHash,
                String.valueOf(issuedAtMillis),
                String.valueOf(expiresAtMillis)));
    }

    /**
     * 校验访问来源、recipient 类型与公开端点标记的一致性。
     */
    private boolean accessKindMatches(FileKeyGrantAccessKind accessKind,
                                      String recipientType,
                                      boolean publicAccess) {
        if (accessKind == null || !StringUtils.hasText(recipientType)
                || publicAccess != (accessKind == FileKeyGrantAccessKind.PUBLIC_SHARE)) {
            return false;
        }
        return switch (accessKind) {
            case OWNER -> FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER.equals(recipientType);
            case ADMIN -> FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER.equals(recipientType);
            case FRIEND_SHARE -> FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE.equals(recipientType);
            case AUTHENTICATED_SHARE, PUBLIC_SHARE ->
                    FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE.equals(recipientType);
        };
    }

    /**
     * 使用常量时间比较固定长度摘要。
     */
    private boolean constantTimeEquals(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right)
                && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * 计算签发上下文对应的调用者摘要。
     */
    private String principalHash(FileKeyGrantIssueContext context) {
        return principalHash(context.envelopeBinding().tenantId(), context.actorId(),
                context.publicClientIdentity(), context.sessionId(), context.publicAccess());
    }

    /**
     * 将调用者身份与高熵下载会话共同摘要，避免 Redis 读者对用户 ID 或客户端 IP 做离线字典反推。
     */
    private String principalHash(Long tenantId,
                                 Long actorId,
                                 String publicClientIdentity,
                                 String sessionId,
                                 boolean publicAccess) {
        String principal = publicAccess
                ? "PUBLIC:" + publicClientIdentity
                : "USER:" + tenantId + ":" + actorId;
        return fingerprint(principal + "\nSESSION:" + sessionId);
    }

    /**
     * 生成 256-bit Base64URL 不透明引用。
     */
    private String newReference() {
        byte[] random = new byte[REFERENCE_BYTES];
        secureRandom.nextBytes(random);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } finally {
            java.util.Arrays.fill(random, (byte) 0);
        }
    }

    /**
     * 使用引用摘要构造 Redis key，避免 Redis 暴露 bearer reference。
     */
    private String redisKey(String reference) {
        return KEY_PREFIX + fingerprint(reference);
    }

    /**
     * 计算 SHA-256 Base64URL 摘要。
     */
    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * 创建 Long 返回类型的 Redis Lua 脚本。
     */
    private DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(text);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 校验安全相关配置边界，错误配置禁止服务启动。
     */
    private void validateConfiguration() {
        if (properties.getGrantTtl() == null || properties.getGrantTtl().isNegative()
                || properties.getGrantTtl().isZero() || properties.getGrantTtl().compareTo(Duration.ofMinutes(5)) > 0
                || properties.getRetryWindow() == null || properties.getRetryWindow().isNegative()
                || properties.getRetryWindow().compareTo(properties.getGrantTtl()) >= 0
                || properties.getMaxSameSessionRetries() < 0
                || properties.getMaxSameSessionRetries() > 3
                || properties.getLegacyPlaintextNotAfter() == null
                || properties.getLegacyPlaintextNotAfter().isAfter(HARD_LEGACY_PLAINTEXT_DEADLINE)) {
            throw new IllegalStateException("Invalid file key delivery grant configuration");
        }
    }

    /**
     * 记录低基数 grant 指标。
     */
    private void metric(String operation, FileKeyGrantAccessKind kind, String outcome) {
        metric(operation, kind, outcome, "NONE");
    }

    /**
     * 记录带闭集稳定原因的低基数 grant 指标，不使用任何请求或资源标识作为标签。
     */
    private void metric(String operation,
                        FileKeyGrantAccessKind kind,
                        String outcome,
                        String reason) {
        meterRegistry.counter("app.file.key.grant", "operation", operation,
                "access_kind", kind.name().toLowerCase(java.util.Locale.ROOT),
                "outcome", outcome, "reason", reason).increment();
    }

    /**
     * 从信封 recipient 类型推断兼容交付的低基数访问类型。
     */
    private FileKeyGrantAccessKind accessKind(FileKeyGrantEnvelopeBinding binding) {
        if (FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE.equals(binding.recipientType())) {
            return FileKeyGrantAccessKind.FRIEND_SHARE;
        }
        if (FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE.equals(binding.recipientType())) {
            return FileKeyGrantAccessKind.AUTHENTICATED_SHARE;
        }
        return FileKeyGrantAccessKind.OWNER;
    }

    /**
     * 把 Lua 结果转换为稳定拒绝原因。
     */
    private String reservationReason(Long result) {
        if (Long.valueOf(-1L).equals(result)) {
            return "GRANT_MISSING_OR_EXPIRED";
        }
        if (Long.valueOf(-2L).equals(result)) {
            return "BINDING_MISMATCH";
        }
        if (Long.valueOf(-3L).equals(result)) {
            return "SESSION_OR_PRINCIPAL_MISMATCH";
        }
        return "GRANT_REPLAYED";
    }

    /**
     * 从受控异常中生成不会泄露动态数据的审计原因。
     */
    private String stableReason(GeneralException exception) {
        Object data = exception.getData();
        return data instanceof String reason && reason.matches("[A-Z0-9_]{3,64}")
                ? reason
                : "GRANT_DENIED";
    }

    /**
     * 构造统一失败关闭的授权异常。
     */
    private GeneralException denied(String reason) {
        return new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, reason);
    }

    /**
     * 构造不泄露 Redis 细节的临时不可用异常。
     */
    private GeneralException unavailable() {
        return new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "KEY_GRANT_UNAVAILABLE");
    }

    /**
     * 读取必填文本字段。
     */
    private String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("missing field");
        }
        return value;
    }

    /**
     * 读取必填 Long 字段。
     */
    private Long requiredLong(Map<String, String> fields, String key) {
        try {
            return Long.valueOf(required(fields, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid long field", exception);
        }
    }

    /**
     * 读取必填 Integer 字段。
     */
    private Integer requiredInteger(Map<String, String> fields, String key) {
        try {
            return Integer.valueOf(required(fields, key));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid integer field", exception);
        }
    }

    /**
     * 严格读取布尔字段，拒绝 Boolean.parseBoolean 的静默降级。
     */
    private boolean requiredBoolean(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * 读取允许为空的 Long 字段。
     */
    private Long nullableLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid nullable long field", exception);
        }
    }

    /**
     * 把可空绑定值安全编码为 Redis 文本。
     */
    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 将遗留空版本规范化为历史首版本，避免产生可跨版本消费的 grant。
     */
    private Integer normalizedFileVersion(File file) {
        return file != null && file.getVersion() != null ? file.getVersion() : 1;
    }

    /**
     * Redis 中解码后的非秘密 grant 状态。
     */
    private record StoredGrant(
            FileKeyGrantEnvelopeBinding binding,
            FileKeyGrantAccessKind accessKind,
            boolean publicAccess,
            String bindingHash,
            String sessionHash,
            String principalHash,
            long expiresAtMillis
    ) {
    }
}
