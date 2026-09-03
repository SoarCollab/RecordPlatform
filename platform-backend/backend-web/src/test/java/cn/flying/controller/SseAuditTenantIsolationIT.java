package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.common.util.TenantKeyUtils;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 匿名握手的真实 MySQL/Redis 租户与审计隔离集成测试。
 */
@Transactional
@DisplayName("SSE trusted tenant and audit integration tests")
class SseAuditTenantIsolationIT extends BaseControllerIntegrationTest {

    private static final String CONNECT_PATH = "/api/v1/sse/connect";
    private static final String CONNECT_METHOD = "cn.flying.controller.SseController.connect";
    private static final long TRUSTED_TENANT_ID = 7_101L;
    private static final long FORGED_TENANT_ID = 7_102L;
    private static final long OTHER_TENANT_ID = 7_103L;
    private static final long SSE_USER_ID = 8_101L;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SseEmitterManager sseEmitterManager;

    private final Set<String> redisKeys = new LinkedHashSet<>();

    /**
     * 为每个场景提供可控 emitter，并隔离当前测试的审计数据。
     */
    @BeforeEach
    void setUpSseAuditTest() {
        when(sseEmitterManager.createConnection(anyLong(), anyLong(), anyString()))
                .thenReturn(new SseEmitter(60_000L));
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE method = ?", CONNECT_METHOD);
    }

    /**
     * 清理 Redis 测试 key 与线程上下文，避免容器复用污染后续测试。
     */
    @AfterEach
    void cleanUpSseAuditTest() {
        if (!redisKeys.isEmpty()) {
            redisTemplate.delete(redisKeys);
        }
        TenantContext.clear();
        MDC.clear();
    }

    /**
     * 验证调用者选择的租户不能接收无效 token 的失败审计。
     */
    @Test
    @DisplayName("invalid token should not pollute a forged tenant audit")
    void invalidTokenShouldNotPolluteForgedTenantAudit() throws Exception {
        String invalidToken = "invalid-" + UUID.randomUUID().toString().replace("-", "");
        String requestIp = "198.51.100.41";

        performConnect(invalidToken, FORGED_TENANT_ID, requestIp)
                .andExpect(status().isUnauthorized());

        assertFailureAuditIsSystemTenant(requestIp, invalidToken);
    }

    /**
     * 验证 namespace 中的载荷 tenant 与提示不一致时 token 被消费但身份不被信任。
     */
    @Test
    @DisplayName("embedded tenant mismatch should consume the token and fail closed")
    void embeddedTenantMismatchShouldConsumeTokenAndFailClosed() throws Exception {
        String mismatchedToken = "mismatch-" + UUID.randomUUID().toString().replace("-", "");
        String redisKey = sseRedisKey(FORGED_TENANT_ID, mismatchedToken);
        redisTemplate.opsForValue().set(
                redisKey,
                SSE_USER_ID + ":" + OTHER_TENANT_ID + ":user",
                Const.SSE_TOKEN_TTL,
                TimeUnit.SECONDS);
        String requestIp = "198.51.100.42";

        performConnect(mismatchedToken, FORGED_TENANT_ID, requestIp)
                .andExpect(status().isUnauthorized());

        assertThat(redisTemplate.hasKey(redisKey)).isFalse();
        assertFailureAuditIsSystemTenant(requestIp, mismatchedToken);
    }

    /**
     * 验证合法 token 的 emitter、请求用户和数据库 tenant 均来自 token。
     *
     */
    @Test
    @DisplayName("valid token should establish trusted emitter and audit identity")
    void validTokenShouldEstablishTrustedEmitterAndAuditIdentity() throws Exception {
        String validToken = createValidToken();
        String requestIp = "198.51.100.43";

        performConnect(validToken, TRUSTED_TENANT_ID, requestIp)
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(request().attribute(Const.ATTR_TENANT_ID, TRUSTED_TENANT_ID))
                .andExpect(request().attribute(Const.ATTR_USER_ID, SSE_USER_ID))
                .andExpect(request().attribute(Const.ATTR_USER_ROLE, "user"));

        AuditRow row = findSingleAudit(requestIp);
        assertThat(row.tenantId()).isEqualTo(TRUSTED_TENANT_ID);
        assertThat(row.status()).isZero();
        assertThat(row.userId()).isEqualTo(String.valueOf(SSE_USER_ID));
        assertThat(row.requestParam()).isNull();
        assertAuditDoesNotContain(row, validToken);
        verify(sseEmitterManager, times(1))
                .createConnection(eq(TRUSTED_TENANT_ID), eq(SSE_USER_ID), anyString());
    }

    /**
     * 验证一次性 token 再次使用时失败且只产生 system tenant 审计。
     */
    @Test
    @DisplayName("consumed token should not be replayed")
    void consumedTokenShouldNotBeReplayed() throws Exception {
        String validToken = createValidToken();
        performConnect(validToken, TRUSTED_TENANT_ID, "198.51.100.43")
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        String requestIp = "198.51.100.44";

        performConnect(validToken, TRUSTED_TENANT_ID, requestIp)
                .andExpect(status().isUnauthorized());

        assertFailureAuditIsSystemTenant(requestIp, validToken);
        verify(sseEmitterManager, times(1))
                .createConnection(eq(TRUSTED_TENANT_ID), eq(SSE_USER_ID), anyString());
    }

    /**
     * 创建一个生产兼容的 tenant-scoped 一次性 SSE token。
     */
    private String createValidToken() {
        String validToken = TenantContext.callWithTenant(
                TRUSTED_TENANT_ID,
                () -> jwtUtils.createSseToken(SSE_USER_ID, TRUSTED_TENANT_ID, "user", 0L));
        sseRedisKey(TRUSTED_TENANT_ID, validToken);
        return validToken;
    }

    /**
     * 发起不带普通 JWT 的 SSE 短令牌握手请求。
     */
    private org.springframework.test.web.servlet.ResultActions performConnect(
            String token,
            long tenantHint,
            String requestIp) throws Exception {
        return mockMvc.perform(get(CONNECT_PATH)
                .param("token", token)
                .param("tenantId", String.valueOf(tenantHint))
                .with(remoteAddress(requestIp))
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    /**
     * 验证失败审计固定落在 system tenant、标记失败且不包含短 token。
     */
    private void assertFailureAuditIsSystemTenant(String requestIp, String token) {
        AuditRow row = findSingleAudit(requestIp);
        assertThat(row.tenantId()).isZero();
        assertThat(row.status()).isEqualTo(1);
        assertThat(row.userId()).isNull();
        assertThat(row.requestParam()).isNull();
        assertThat(row.errorMsg()).startsWith("HTTP ");
        assertAuditDoesNotContain(row, token);
    }

    /**
     * 查询一个请求 IP 对应的唯一 SSE connect 审计行。
     */
    private AuditRow findSingleAudit(String requestIp) {
        List<AuditRow> rows = jdbcTemplate.query(
                """
                SELECT tenant_id, status, user_id, request_param, error_msg, response_result
                FROM sys_operation_log
                WHERE method = ? AND request_ip = ?
                """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getLong("tenant_id"),
                        resultSet.getInt("status"),
                        resultSet.getString("user_id"),
                        resultSet.getString("request_param"),
                        resultSet.getString("error_msg"),
                        resultSet.getString("response_result")),
                CONNECT_METHOD,
                requestIp);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    /**
     * 验证所有可持久化审计文本均不包含一次性 token 原文。
     */
    private void assertAuditDoesNotContain(AuditRow row, String token) {
        List<String> persistedText = java.util.stream.Stream.of(
                        row.requestParam(), row.errorMsg(), row.responseResult())
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(persistedText).noneMatch(value -> value.contains(token));
    }

    /**
     * 构造并登记与生产兼容的 tenant-scoped SSE Redis key。
     */
    private String sseRedisKey(long tenantId, String token) {
        String key = TenantKeyUtils.tenantKey(Const.SSE_TOKEN_PREFIX + token, tenantId);
        redisKeys.add(key);
        return key;
    }

    /**
     * 设置 MockMvc 请求的直接 socket 对端地址。
     */
    private RequestPostProcessor remoteAddress(String peer) {
        return request -> {
            request.setRemoteAddr(peer);
            return request;
        };
    }

    private record AuditRow(
            long tenantId,
            int status,
            String userId,
            String requestParam,
            String errorMsg,
            String responseResult) {
    }
}
