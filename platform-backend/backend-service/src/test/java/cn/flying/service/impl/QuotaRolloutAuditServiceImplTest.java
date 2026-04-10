package cn.flying.service.impl;

import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.QuotaRolloutAudit;
import cn.flying.dao.mapper.QuotaRolloutAuditMapper;
import cn.flying.dao.vo.file.QuotaRolloutAuditUpsertVO;
import cn.flying.dao.vo.file.QuotaRolloutAuditVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuotaRolloutAuditServiceImpl 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class QuotaRolloutAuditServiceImplTest {

    @Mock
    private QuotaRolloutAuditMapper quotaRolloutAuditMapper;

    @InjectMocks
    private QuotaRolloutAuditServiceImpl service;

    private MockedStatic<IdUtils> idUtilsMock;

    private QuotaRolloutAuditUpsertVO request;

    /**
     * 初始化测试请求样例。
     */
    @BeforeEach
    void setUp() {
        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(IdUtils::nextEntityId).thenReturn(1L);

        request = new QuotaRolloutAuditUpsertVO(
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                120L,
                6L,
                "KEEP_ENFORCE",
                "持续观察无异常",
                "https://example.com/evidence/1"
        );
    }

    @AfterEach
    void tearDown() {
        if (idUtilsMock != null) {
            idUtilsMock.close();
        }
    }

    /**
     * 验证写入成功后会返回最新审计记录。
     */
    @Test
    void shouldUpsertAndReturnLatestAudit() {
        QuotaRolloutAudit audit = new QuotaRolloutAudit()
                .setId(9L)
                .setBatchId("batch-a")
                .setTenantId(1L)
                .setObservationStartTime(toDate(LocalDateTime.of(2026, 2, 1, 0, 0)))
                .setObservationEndTime(toDate(LocalDateTime.of(2026, 2, 7, 0, 0)))
                .setSampledRequestCount(1000L)
                .setExceededRequestCount(120L)
                .setFalsePositiveCount(6L)
                .setRollbackDecision("KEEP_ENFORCE")
                .setRollbackReason("持续观察无异常")
                .setEvidenceLink("https://example.com/evidence/1")
                .setOperatorName("uid:88")
                .setCreateTime(toDate(LocalDateTime.of(2026, 2, 7, 1, 0)))
                .setUpdateTime(toDate(LocalDateTime.of(2026, 2, 7, 1, 30)));

        when(quotaRolloutAuditMapper.upsertAudit(any(QuotaRolloutAudit.class))).thenReturn(1);
        when(quotaRolloutAuditMapper.selectByBatchAndTenant("batch-a", 1L)).thenReturn(audit);

        QuotaRolloutAuditVO result = service.upsertAudit(88L, request);

        assertEquals(9L, result.id());
        assertEquals("batch-a", result.batchId());
        assertEquals(1L, result.tenantId());
        assertEquals("KEEP_ENFORCE", result.rollbackDecision());
        assertEquals(0.05D, result.falsePositiveRate());
        assertEquals("uid:88", result.operatorName());
        verify(quotaRolloutAuditMapper).upsertAudit(any(QuotaRolloutAudit.class));
    }

    /**
     * 验证观察窗口开始时间晚于结束时间时会被拒绝。
     */
    @Test
    void shouldRejectWhenObservationWindowInvalid() {
        QuotaRolloutAuditUpsertVO invalidRequest = new QuotaRolloutAuditUpsertVO(
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 8, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                120L,
                6L,
                "KEEP_ENFORCE",
                "",
                ""
        );

        assertThrows(GeneralException.class, () -> service.upsertAudit(88L, invalidRequest));
    }

    /**
     * 验证误判数超过命中数时会被拒绝。
     */
    @Test
    void shouldRejectWhenFalsePositiveExceedsExceeded() {
        QuotaRolloutAuditUpsertVO invalidRequest = new QuotaRolloutAuditUpsertVO(
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                10L,
                11L,
                "KEEP_ENFORCE",
                "",
                ""
        );

        assertThrows(GeneralException.class, () -> service.upsertAudit(88L, invalidRequest));
    }

    /**
     * 验证 FORCE_SHADOW 决策缺少原因时会被拒绝。
     */
    @Test
    void shouldRejectForceShadowWithoutReason() {
        QuotaRolloutAuditUpsertVO invalidRequest = new QuotaRolloutAuditUpsertVO(
                "batch-a",
                1L,
                LocalDateTime.of(2026, 2, 1, 0, 0),
                LocalDateTime.of(2026, 2, 7, 0, 0),
                1000L,
                120L,
                6L,
                "FORCE_SHADOW",
                "   ",
                ""
        );

        assertThrows(GeneralException.class, () -> service.upsertAudit(88L, invalidRequest));
    }

    /**
     * 验证查询不存在记录时抛出异常。
     */
    @Test
    void shouldThrowWhenAuditNotFound() {
        when(quotaRolloutAuditMapper.selectByBatchAndTenant("batch-a", 1L)).thenReturn(null);

        assertThrows(GeneralException.class, () -> service.getLatestAudit("batch-a", 1L));
    }

    /**
     * 将 LocalDateTime 转换为 Date，便于构造实体样例。
     *
     * @param value 本地时间
     * @return Date 对象
     */
    private Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
}
