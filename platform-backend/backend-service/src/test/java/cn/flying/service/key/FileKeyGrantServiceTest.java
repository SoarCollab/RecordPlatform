package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FriendFileShareMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileKeyGrantService 的绑定、重放与失败关闭单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FileKeyGrantServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long FILE_ID = 11L;
    private static final Long ACTOR_ID = 13L;
    private static final Long FRIEND_ACTOR_ID = 14L;
    private static final String FILE_HASH = "hash-001";
    private static final String SESSION_ID = "download-session-123456";
    private static final Instant NOW = Instant.parse("2026-07-27T08:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FriendFileShareMapper friendFileShareMapper;

    @Mock
    private FileKeyEnvelopeService envelopeService;

    @Mock
    private SecureRandom secureRandom;

    private File file;
    private FileKeyGrantEnvelopeBinding binding;
    private FileKeyDeliveryProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private FileKeyGrantService service;

    /**
     * 初始化固定时钟、随机源和完整信封绑定。
     */
    @BeforeEach
    void setUp() {
        properties = new FileKeyDeliveryProperties();
        properties.setGrantTtl(Duration.ofSeconds(60));
        properties.setRetryWindow(Duration.ofSeconds(10));
        properties.setMaxSameSessionRetries(1);
        meterRegistry = new SimpleMeterRegistry();
        file = new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(ACTOR_ID)
                .setVersion(3)
                .setFileHash(FILE_HASH);
        binding = new FileKeyGrantEnvelopeBinding(
                101L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, ACTOR_ID, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        service = new FileKeyGrantService(
                redisTemplate, fileMapper, fileShareMapper, friendFileShareMapper,
                envelopeService, properties,
                meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), secureRandom);
        TenantContext.setTenantId(TENANT_ID);
    }

    /**
     * 清理线程租户上下文，避免测试间泄漏。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证多构造函数服务显式标记生产注入入口，避免完整 Spring 上下文退化为查找无参构造。
     */
    @Test
    void shouldDeclareProductionConstructorForSpringInjection() {
        var productionConstructors = java.util.Arrays.stream(FileKeyGrantService.class.getConstructors())
                .filter(constructor -> constructor.getParameterCount() == 7)
                .toList();

        assertThat(productionConstructors).singleElement()
                .satisfies(constructor -> assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue());
    }

    /**
     * 验证 grant 创建只保存摘要和非秘密绑定，随后可被精确消费。
     */
    @Test
    void shouldIssueAndConsumeExactBindingWithoutPersistingSecrets() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L, 1L);

        var grant = service.issue(authenticatedContext());
        Map<Object, Object> stored = storedFields(issueArguments.getValue());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(stored);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(envelopeService.unwrapGrantBinding(
                file, binding, ACTOR_ID, "DOWNLOAD_GRANT_CONSUME"))
                .thenReturn(Optional.of("c2VjcmV0LWtleQ=="));

        var material = service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID);

        assertThat(grant.reference()).hasSize(43);
        assertThat(grant.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(material.initialKey()).isEqualTo("c2VjcmV0LWtleQ==");
        assertThat(stored.values())
                .noneMatch(value -> String.valueOf(value).contains(grant.reference()))
                .noneMatch(value -> String.valueOf(value).contains("c2VjcmV0LWtleQ=="));
        assertThat(stored).containsEntry("state", "ISSUED")
                .containsEntry("fileVersion", "3")
                .containsEntry("kmsProvider", "vault-transit");
        verify(envelopeService).auditGrantIssue(binding, ACTOR_ID, "OWNER");
    }

    /**
     * 验证跨会话消费在 Lua 预留和 KMS 解封前失败。
     */
    @Test
    void shouldRejectCrossSessionBeforeReserveOrUnwrap() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));

        assertThatThrownBy(() -> service.consumeAuthenticated(
                grant.reference(), "different-session-1234", ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("SESSION_MISMATCH");

        verify(fileMapper, never()).selectById(any());
        verify(envelopeService, never()).unwrapGrantBinding(any(), any(), any(), any());
        verify(envelopeService).auditGrantDenial(binding, ACTOR_ID, "SESSION_MISMATCH");
    }

    /**
     * 验证原子状态机允许同会话受控重试并使用明确审计原因。
     */
    @Test
    void shouldAllowBoundedSameSessionRetry() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L, 2L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(envelopeService.unwrapGrantBinding(
                file, binding, ACTOR_ID, "DOWNLOAD_GRANT_RETRY"))
                .thenReturn(Optional.of("c2VjcmV0LWtleQ=="));

        var material = service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID);

        assertThat(material.protocol()).isEqualTo(FileKeyGrantService.PROTOCOL_GRANT_V1);
        Object[] reserveArguments = issueArguments.getAllValues().get(1);
        assertThat(reserveArguments).hasSize(6);
        assertThat(reserveArguments[3]).isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertThat(reserveArguments[4]).isEqualTo("10000");
        assertThat(reserveArguments[5]).isEqualTo("1");
        verify(envelopeService).unwrapGrantBinding(
                file, binding, ACTOR_ID, "DOWNLOAD_GRANT_RETRY");
    }

    /**
     * 验证公开 grant 同时绑定公开端点和规范化客户端身份。
     */
    @Test
    void shouldRejectPublicGrantFromDifferentClientIdentity() {
        FileKeyGrantEnvelopeBinding publicBinding = new FileKeyGrantEnvelopeBinding(
                102L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, 77L, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(new FileKeyGrantIssueContext(
                file, publicBinding, FileKeyGrantAccessKind.PUBLIC_SHARE,
                null, "203.0.113.7", SESSION_ID));
        Map<Object, Object> stored = storedFields(issueArguments.getValue());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(stored);

        assertThat(stored.values())
                .noneMatch(value -> String.valueOf(value).contains("203.0.113.7"))
                .noneMatch(value -> String.valueOf(value).contains(SESSION_ID));

        TenantContext.clear();
        doAnswer(invocation -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(TENANT_ID);
            return null;
        }).when(envelopeService).auditGrantDenial(
                publicBinding, null, "PRINCIPAL_MISMATCH");

        assertThatThrownBy(() -> service.consumePublic(
                grant.reference(), SESSION_ID, "203.0.113.8"))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("PRINCIPAL_MISMATCH");
        assertThat(TenantContext.getTenantId()).isNull();
        verify(envelopeService).auditGrantDenial(publicBinding, null, "PRINCIPAL_MISMATCH");
        assertThat(meterRegistry.find("app.file.key.grant")
                .tags("operation", "consume", "access_kind", "public_share",
                        "outcome", "denied", "reason", "PRINCIPAL_MISMATCH")
                .counter()).isNotNull();
    }

    /**
     * 验证调用者或当前租户变化后不能消费既有 grant。
     */
    @Test
    void shouldRejectDifferentActorAndTenant() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));

        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, 999L))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("PRINCIPAL_MISMATCH");

        TenantContext.setTenantId(99L);
        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("TENANT_MISMATCH");
        verify(fileMapper, never()).selectById(any());
    }

    /**
     * 验证管理员 grant 区分于 owner，并在消费时重新确认当前管理员角色。
     */
    @Test
    void shouldAllowCurrentAdminAndRejectRoleDowngrade() {
        Long ownerId = 99L;
        File adminFile = new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(ownerId)
                .setVersion(3)
                .setFileHash(FILE_HASH);
        FileKeyGrantEnvelopeBinding adminBinding = new FileKeyGrantEnvelopeBinding(
                104L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_OWNER, ownerId, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L, 1L);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(true);
            var grant = service.issue(new FileKeyGrantIssueContext(
                    adminFile, adminBinding, FileKeyGrantAccessKind.ADMIN,
                    ACTOR_ID, null, SESSION_ID));
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
            when(fileMapper.selectById(FILE_ID)).thenReturn(adminFile);
            when(envelopeService.unwrapGrantBinding(
                    adminFile, adminBinding, ACTOR_ID, "DOWNLOAD_GRANT_CONSUME"))
                    .thenReturn(Optional.of("YWRtaW4ta2V5"));

            assertThat(service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID).initialKey())
                    .isEqualTo("YWRtaW4ta2V5");

            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.consumeAuthenticated(
                    grant.reference(), SESSION_ID, ACTOR_ID))
                    .isInstanceOf(GeneralException.class)
                    .extracting("data")
                    .isEqualTo("ADMIN_AUTHORIZATION_CHANGED");
        }
    }

    /**
     * 验证 TTL 到期后在文件或信封读取前失败关闭。
     */
    @Test
    void shouldRejectExpiredGrant() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        FileKeyGrantService expiredService = newService(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredService.consumeAuthenticated(
                grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("GRANT_EXPIRED");
        verify(fileMapper, never()).selectById(any());
    }

    /**
     * 验证文件版本变化会在原子预留和 KMS 解封前撤销旧 grant。
     */
    @Test
    void shouldRejectFileVersionChangeBeforeReservation() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        when(fileMapper.selectById(FILE_ID)).thenReturn(new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(ACTOR_ID)
                .setVersion(4)
                .setFileHash(FILE_HASH));

        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("FILE_BINDING_MISMATCH");
        verify(envelopeService, never()).unwrapGrantBinding(any(), any(), any(), any());
    }

    /**
     * 验证 rotation 或撤销导致精确信封不再 ACTIVE 时不释放密钥。
     */
    @Test
    void shouldRejectRevokedOrRotatedEnvelope() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L, 1L);
        var grant = service.issue(authenticatedContext());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(envelopeService.unwrapGrantBinding(
                file, binding, ACTOR_ID, "DOWNLOAD_GRANT_CONSUME"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("ENVELOPE_NOT_ACTIVE");
    }

    /**
     * 验证公开分享改为私密后，已签发匿名 grant 在预留和解封前立即失效。
     */
    @Test
    void shouldRejectPublicGrantAfterShareVisibilityIsRestricted() {
        FileKeyGrantEnvelopeBinding publicBinding = new FileKeyGrantEnvelopeBinding(
                102L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE, 77L, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(new FileKeyGrantIssueContext(
                file, publicBinding, FileKeyGrantAccessKind.PUBLIC_SHARE,
                null, "203.0.113.7", SESSION_ID));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(fileShareMapper.selectById(77L)).thenReturn(new FileShare()
                .setId(77L)
                .setTenantId(TENANT_ID)
                .setUserId(ACTOR_ID)
                .setShareType(1)
                .setFileHashes("[\"" + FILE_HASH + "\"]")
                .setExpireTime(java.util.Date.from(NOW.plusSeconds(300)))
                .setStatus(FileShare.STATUS_ACTIVE));

        assertThatThrownBy(() -> service.consumePublic(
                grant.reference(), SESSION_ID, "203.0.113.7"))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("SHARE_AUTHORIZATION_CHANGED");
        verify(envelopeService, never()).unwrapGrantBinding(any(), any(), any(), any());
    }

    /**
     * 验证好友分享取消后，已签发 grant 不能依赖陈旧 ACTIVE 信封继续消费。
     */
    @Test
    void shouldRejectGrantAfterFriendShareCancellation() {
        FileKeyGrantEnvelopeBinding friendBinding = new FileKeyGrantEnvelopeBinding(
                103L, TENANT_ID, FILE_ID, 3, FILE_HASH,
                FileKeyEnvelopeService.RECIPIENT_TYPE_FRIEND_SHARE, 88L, 4,
                "RP-AES256-GCM-FRAMED-V2", "UNSIGNED-V1", "NONE-V1",
                "RP-MERKLE-SHA256-V1", "FRAMED_AEAD_V2", "vault-transit",
                1, "9", false);
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(new FileKeyGrantIssueContext(
                file, friendBinding, FileKeyGrantAccessKind.FRIEND_SHARE,
                FRIEND_ACTOR_ID, null, SESSION_ID));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(storedFields(issueArguments.getValue()));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(friendFileShareMapper.selectById(88L)).thenReturn(new FriendFileShare()
                .setId(88L)
                .setTenantId(TENANT_ID)
                .setSharerId(ACTOR_ID)
                .setFriendId(FRIEND_ACTOR_ID)
                .setFileHashes("[\"" + FILE_HASH + "\"]")
                .setStatus(FriendFileShare.STATUS_CANCELLED));

        assertThatThrownBy(() -> service.consumeAuthenticated(
                grant.reference(), SESSION_ID, FRIEND_ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("FRIEND_AUTHORIZATION_CHANGED");
        verify(envelopeService, never()).unwrapGrantBinding(any(), any(), any(), any());
    }

    /**
     * 验证 Redis 状态字段被改写后绑定摘要校验失败，不会被重定向到其他文件。
     */
    @Test
    void shouldRejectTamperedRedisBinding() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        Map<Object, Object> stored = storedFields(issueArguments.getValue());
        stored.put("fileId", "999");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(stored);

        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("GRANT_STATE_INVALID");
        verify(fileMapper, never()).selectById(any());
    }

    /**
     * 验证 Redis 布尔字段不能通过宽松解析静默降级为另一种信封类型。
     */
    @Test
    void shouldRejectNonCanonicalRedisBoolean() {
        ArgumentCaptor<Object[]> issueArguments = ArgumentCaptor.forClass(Object[].class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), issueArguments.capture()))
                .thenReturn(1L);
        var grant = service.issue(authenticatedContext());
        Map<Object, Object> stored = storedFields(issueArguments.getValue());
        stored.put("legacyPlaintextAtRest", "FALSE");
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(any())).thenReturn(stored);

        assertThatThrownBy(() -> service.consumeAuthenticated(grant.reference(), SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("GRANT_STATE_INVALID");
        verify(fileMapper, never()).selectById(any());
    }

    /**
     * 验证非法引用与 Redis 故障均使用稳定失败关闭原因且不会暴露底层错误。
     */
    @Test
    void shouldFailClosedForMalformedReferenceAndRedisOutage() {
        assertThatThrownBy(() -> service.consumeAuthenticated("bad", SESSION_ID, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("GRANT_REFERENCE_INVALID");

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis endpoint secret"));
        assertThatThrownBy(() -> service.issue(authenticatedContext()))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("KEY_GRANT_UNAVAILABLE");
        verify(envelopeService).auditGrantDenial(binding, ACTOR_ID, "GRANT_ISSUE_UNAVAILABLE");
    }

    /**
     * 验证旧明文协议默认关闭且截止时间到达后不能被配置重新打开。
     */
    @Test
    void shouldKeepLegacyPlaintextDisabledAndDeadlineBounded() {
        assertThat(service.isLegacyPlaintextAllowed()).isFalse();
        properties.setLegacyPlaintextEnabled(true);
        properties.setLegacyPlaintextNotAfter(NOW);

        assertThat(service.isLegacyPlaintextAllowed()).isFalse();
        assertThatThrownBy(() -> service.deliverLegacyPlaintext(file, binding, ACTOR_ID))
                .isInstanceOf(GeneralException.class)
                .extracting("data")
                .isEqualTo("LEGACY_PROTOCOL_DISABLED");
        verify(envelopeService, never()).unwrapGrantBinding(any(), any(), any(), any());
    }

    /**
     * 验证运维配置不能把编译期硬截止时间延后为永久兼容后门。
     */
    @Test
    void shouldRejectLegacyDeadlineExtensionAtStartup() {
        properties.setLegacyPlaintextNotAfter(Instant.parse("2026-10-01T00:00:01Z"));

        assertThatThrownBy(() -> newService(Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid file key delivery grant configuration");
    }

    /**
     * 构造认证 owner grant 上下文。
     */
    private FileKeyGrantIssueContext authenticatedContext() {
        return new FileKeyGrantIssueContext(
                file, binding, FileKeyGrantAccessKind.OWNER, ACTOR_ID, null, SESSION_ID);
    }

    /**
     * 使用相同依赖和指定时钟构造服务实例。
     */
    private FileKeyGrantService newService(Clock testClock) {
        return new FileKeyGrantService(
                redisTemplate, fileMapper, fileShareMapper, friendFileShareMapper,
                envelopeService, properties,
                meterRegistry, testClock, secureRandom);
    }

    /**
     * 将 issue Lua 的 field/value 参数还原为后续消费读取的 Redis hash。
     */
    private Map<Object, Object> storedFields(Object[] arguments) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            fields.put(arguments[index], arguments[index + 1]);
        }
        return fields;
    }
}
