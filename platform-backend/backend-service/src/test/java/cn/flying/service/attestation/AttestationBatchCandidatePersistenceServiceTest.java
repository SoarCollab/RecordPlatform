package cn.flying.service.attestation;

import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.mapper.AttestationBatchCandidateMapper;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationBatchCandidatePersistenceServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private AttestationBatchCandidateMapper candidateMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AttestationBatchCandidatePersistenceService service;

    /**
     * 初始化 candidate 短事务持久化服务。
     */
    @BeforeEach
    void setUp() {
        service = new AttestationBatchCandidatePersistenceService(candidateMapper, snowflakeIdGenerator);
    }

    /**
     * 验证唯一 active manifest 进入 READY，重复 active manifest 直接进入 dead-letter。
     */
    @Test
    void seedEligibleCandidatesShouldSeparateValidAndInvalidEvidence() {
        AttestationBatchCandidate valid = source(11L, 101L, 1, "sha256:" + "a".repeat(64));
        AttestationBatchCandidate duplicateActive = source(12L, 102L, 2, "sha256:" + "b".repeat(64));
        AttestationBatchCandidate missingHash = source(13L, 103L, 1, null);
        when(candidateMapper.selectEligibleSources(TENANT_ID, 1, 20))
                .thenReturn(List.of(valid, duplicateActive, missingHash));
        when(snowflakeIdGenerator.nextId()).thenReturn(1_001L, 1_002L, 1_003L);
        when(candidateMapper.insertIgnoreBatch(anyList())).thenReturn(1, 2);

        AttestationCandidateAdmissionResult result = service.seedEligibleCandidates(
                TENANT_ID, 20, Date.from(NOW));

        assertThat(result.readyCandidates()).isEqualTo(1);
        assertThat(result.deadLetterCandidates()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AttestationBatchCandidate>> captor = ArgumentCaptor.forClass(List.class);
        verify(candidateMapper, org.mockito.Mockito.times(2)).insertIgnoreBatch(captor.capture());
        assertThat(captor.getAllValues().get(0).getFirst().getStatus()).isEqualTo("READY");
        assertThat(captor.getAllValues().get(0).getFirst().getEvidenceType()).isEqualTo("MANIFEST_HASH");
        assertThat(captor.getAllValues().get(1).getFirst().getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(captor.getAllValues().get(1).getFirst().getLastError())
                .contains("exactly one active manifest");
        assertThat(captor.getAllValues().get(1).get(1).getEvidenceHash()).isNull();
        assertThat(captor.getAllValues().get(1).get(1).getLastError())
                .contains("canonical sha256 evidence");
    }

    /**
     * 验证领取使用 token、租约和数据库递增后的 attempt 快照。
     */
    @Test
    void claimCandidatesShouldReturnReloadedTokenProtectedSnapshot() {
        AttestationBatchCandidate claimable = persistedCandidate(1_001L, 11L)
                .setStatus("READY")
                .setAttemptCount(0);
        AttestationBatchCandidate claimed = persistedCandidate(1_001L, 11L)
                .setStatus("CLAIMED")
                .setAttemptCount(1);
        when(candidateMapper.selectClaimableForUpdate(TENANT_ID, Date.from(NOW), 3, 10))
                .thenReturn(List.of(claimable));
        when(candidateMapper.claimSelected(
                eq(TENANT_ID), eq(List.of(1_001L)), anyString(), eq(Date.from(NOW)), any(Date.class)))
                .thenReturn(1);
        when(candidateMapper.selectClaimedByToken(eq(TENANT_ID), anyString()))
                .thenAnswer(invocation -> {
                    claimed.setClaimToken(invocation.getArgument(1));
                    return List.of(claimed);
                });

        AttestationCandidateClaim claim = service.claimCandidates(TENANT_ID, 10, 3, 120, NOW);

        assertThat(claim).isNotNull();
        assertThat(claim.size()).isEqualTo(1);
        assertThat(claim.claimToken()).hasSize(36);
        assertThat(claim.candidates().getFirst().getAttemptCount()).isEqualTo(1);
        verify(candidateMapper).markExpiredExhausted(TENANT_ID, Date.from(NOW), 3);
    }

    /**
     * 验证批量领取注解脚本可被 MyBatis XML 驱动解析，防止 foreach 属性拼写破坏生产领取。
     */
    @Test
    void claimSelectedAnnotationShouldBeValidMyBatisXml() throws NoSuchMethodException {
        Method method = AttestationBatchCandidateMapper.class.getMethod(
                "claimSelected",
                Long.class,
                List.class,
                String.class,
                Date.class,
                Date.class);
        Update update = method.getAnnotation(Update.class);

        SqlSource sqlSource = new XMLLanguageDriver().createSqlSource(
                new Configuration(),
                String.join(" ", update.value()),
                Map.class);

        assertThat(sqlSource).isNotNull();
    }

    /**
     * 验证创建失败达到最大次数时释放 claim 并报告 dead-letter 数量。
     */
    @Test
    void releaseClaimShouldReportCandidatesThatReachDeadLetter() {
        AttestationBatchCandidate candidate = persistedCandidate(1_001L, 11L)
                .setStatus("CLAIMED")
                .setClaimToken("claim")
                .setAttemptCount(3);
        AttestationCandidateClaim claim = new AttestationCandidateClaim(
                TENANT_ID, "claim", List.of(candidate));
        when(candidateMapper.releaseClaim(TENANT_ID, "claim", 3, "database failure"))
                .thenReturn(1);

        int deadLettered = service.releaseClaim(claim, 3, "database failure");

        assertThat(deadLettered).isEqualTo(1);
    }

    /**
     * 验证重启恢复会先终结已耗尽 claim，再释放仍可重试的过期 claim。
     */
    @Test
    void recoverExpiredClaimsShouldHandleExhaustedAndRetryableRows() {
        when(candidateMapper.markExpiredExhausted(TENANT_ID, Date.from(NOW), 3)).thenReturn(2);
        when(candidateMapper.releaseExpiredRetryable(TENANT_ID, Date.from(NOW), 3)).thenReturn(4);

        int deadLettered = service.recoverExpiredClaims(TENANT_ID, 3, NOW);

        assertThat(deadLettered).isEqualTo(2);
        verify(candidateMapper).releaseExpiredRetryable(TENANT_ID, Date.from(NOW), 3);
    }

    /**
     * 构造候选发现 SQL 返回的 manifest 源行。
     */
    private AttestationBatchCandidate source(Long fileId,
                                             Long manifestId,
                                             int activeManifestCount,
                                             String manifestHash) {
        return new AttestationBatchCandidate()
                .setTenantId(TENANT_ID)
                .setFileId(fileId)
                .setFileVersion(2)
                .setManifestId(manifestId)
                .setEvidenceHash(manifestHash)
                .setChainRecordId("chain-" + fileId)
                .setActiveManifestCount(activeManifestCount);
    }

    /**
     * 构造已持久化且具备完整 manifest 证据的候选。
     */
    private AttestationBatchCandidate persistedCandidate(Long id, Long fileId) {
        return source(fileId, 100L + fileId, 1, "sha256:" + "a".repeat(64))
                .setId(id)
                .setEvidenceType("MANIFEST_HASH")
                .setEligibleAt(Date.from(NOW));
    }
}
