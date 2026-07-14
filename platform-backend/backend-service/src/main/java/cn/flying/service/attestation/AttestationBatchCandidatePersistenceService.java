package cn.flying.service.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.mapper.AttestationBatchCandidateMapper;
import cn.flying.dao.vo.attestation.AttestationBatchCandidateStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用独立短事务发现、领取和释放生产 Merkle batch 候选。
 */
@Service
@RequiredArgsConstructor
public class AttestationBatchCandidatePersistenceService {

    public static final String EVIDENCE_TYPE_MANIFEST_HASH = "MANIFEST_HASH";
    private static final Pattern MANIFEST_HASH_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final int MAX_ERROR_LENGTH = 512;

    private final AttestationBatchCandidateMapper candidateMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 在每轮调度开始时恢复过期 claim，并返回新进入 dead-letter 的数量。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverExpiredClaims(Long tenantId, int maxAttempts, Instant now) {
        Date nowDate = Date.from(now);
        int deadLettered = candidateMapper.markExpiredExhausted(tenantId, nowDate, maxAttempts);
        candidateMapper.releaseExpiredRetryable(tenantId, nowDate, maxAttempts);
        return deadLettered;
    }

    /**
     * 从成功文件和 active manifest 批量 admission 新候选。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationCandidateAdmissionResult seedEligibleCandidates(Long tenantId, int limit, Date admittedAt) {
        List<AttestationBatchCandidate> sources = candidateMapper.selectEligibleSources(
                tenantId, FileUploadStatus.SUCCESS.getCode(), limit);
        if (sources == null || sources.isEmpty()) {
            return new AttestationCandidateAdmissionResult(0, 0);
        }

        List<AttestationBatchCandidate> ready = new ArrayList<>();
        List<AttestationBatchCandidate> deadLetter = new ArrayList<>();
        for (AttestationBatchCandidate source : sources) {
            String validationError = validateSource(source);
            AttestationBatchCandidate candidate = prepareCandidate(source, admittedAt, validationError);
            if (validationError == null) {
                ready.add(candidate);
            } else {
                deadLetter.add(candidate);
            }
        }

        int readyInserted = insertBatchIfPresent(ready);
        int deadInserted = insertBatchIfPresent(deadLetter);
        return new AttestationCandidateAdmissionResult(readyInserted, deadInserted);
    }

    /**
     * 原子领取一页候选，并把过期且耗尽次数的 claim 转入 dead-letter。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AttestationCandidateClaim claimCandidates(Long tenantId,
                                                      int limit,
                                                      int maxAttempts,
                                                      long leaseSeconds,
                                                      Instant now) {
        Date nowDate = Date.from(now);
        candidateMapper.markExpiredExhausted(tenantId, nowDate, maxAttempts);
        List<AttestationBatchCandidate> claimable = candidateMapper.selectClaimableForUpdate(
                tenantId, nowDate, maxAttempts, limit);
        if (claimable == null || claimable.isEmpty()) {
            return null;
        }

        List<Long> candidateIds = claimable.stream()
                .map(AttestationBatchCandidate::getId)
                .toList();
        String claimToken = UUID.randomUUID().toString();
        Date leaseExpiresAt = Date.from(now.plus(leaseSeconds, ChronoUnit.SECONDS));
        int updated = candidateMapper.claimSelected(
                tenantId, candidateIds, claimToken, nowDate, leaseExpiresAt);
        if (updated != candidateIds.size()) {
            throw new IllegalStateException("Candidate claim did not update every locked row");
        }

        List<AttestationBatchCandidate> claimed = candidateMapper.selectClaimedByToken(tenantId, claimToken);
        if (claimed == null || claimed.size() != candidateIds.size()) {
            throw new IllegalStateException("Claimed candidates cannot be reloaded");
        }
        return new AttestationCandidateClaim(tenantId, claimToken, List.copyOf(claimed));
    }

    /**
     * batch 创建失败时释放当前 claim，并返回转入 dead-letter 的候选数。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int releaseClaim(AttestationCandidateClaim claim, int maxAttempts, String errorMessage) {
        if (claim == null || claim.size() == 0) {
            return 0;
        }
        int deadLetterCount = (int) claim.candidates().stream()
                .filter(candidate -> candidate.getAttemptCount() != null
                        && candidate.getAttemptCount() >= maxAttempts)
                .count();
        int updated = candidateMapper.releaseClaim(
                claim.tenantId(), claim.claimToken(), maxAttempts, truncate(errorMessage));
        if (updated != claim.size()) {
            throw new IllegalStateException("Candidate claim release lost ownership");
        }
        return deadLetterCount;
    }

    /**
     * 查询当前租户候选的聚合状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public AttestationBatchCandidateStats stats(Long tenantId) {
        AttestationBatchCandidateStats stats = candidateMapper.selectStats(tenantId);
        return stats != null ? stats : new AttestationBatchCandidateStats();
    }

    /**
     * 把发现结果转换为可插入的候选实体。
     */
    private AttestationBatchCandidate prepareCandidate(AttestationBatchCandidate source,
                                                        Date admittedAt,
                                                        String validationError) {
        return new AttestationBatchCandidate()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(source.getTenantId())
                .setFileId(source.getFileId())
                .setFileVersion(source.getFileVersion())
                .setManifestId(source.getManifestId())
                .setEvidenceType(EVIDENCE_TYPE_MANIFEST_HASH)
                .setEvidenceHash(normalize(source.getEvidenceHash()))
                .setChainRecordId(normalize(source.getChainRecordId()))
                .setStatus(validationError == null
                        ? AttestationBatchCandidateStatus.READY.value()
                        : AttestationBatchCandidateStatus.DEAD_LETTER.value())
                .setAttemptCount(0)
                .setLastError(validationError)
                .setEligibleAt(new Date(admittedAt.getTime()))
                .setDeleted(0);
    }

    /**
     * 校验候选只使用唯一、版本明确且格式正确的 manifest 证据。
     */
    private String validateSource(AttestationBatchCandidate source) {
        if (source == null || source.getFileId() == null || source.getTenantId() == null) {
            return "Candidate source is missing tenant or file identity";
        }
        if (source.getFileVersion() == null || source.getFileVersion() <= 0) {
            return "Candidate source has no valid file version";
        }
        if (!Integer.valueOf(1).equals(source.getActiveManifestCount())) {
            return "File version must have exactly one active manifest";
        }
        if (source.getManifestId() == null) {
            return "Candidate source has no manifest ID";
        }
        if (!isCanonicalManifestHash(source.getEvidenceHash())) {
            return "Active manifest hash is not canonical sha256 evidence";
        }
        if (!StringUtils.hasText(source.getChainRecordId())) {
            return "Successful file has no chain record identifier";
        }
        return null;
    }

    /**
     * 校验文本是否为生产叶子允许的 canonical SHA-256 manifest 证据。
     */
    static boolean isCanonicalManifestHash(String value) {
        return StringUtils.hasText(value)
                && MANIFEST_HASH_PATTERN.matcher(value.trim()).matches();
    }

    /**
     * 仅在列表非空时执行批量 INSERT IGNORE。
     */
    private int insertBatchIfPresent(List<AttestationBatchCandidate> candidates) {
        return candidates.isEmpty() ? 0 : candidateMapper.insertIgnoreBatch(candidates);
    }

    /**
     * 规范化可选文本字段。
     */
    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 把候选错误摘要限制到数据库字段长度。
     */
    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return "Candidate batch creation failed";
        }
        String normalized = value.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }
}
