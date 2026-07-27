package cn.flying.service.key;

import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Owns the short database transaction that changes the readable envelope authority.
 */
@Service
@RequiredArgsConstructor
public class KeyEnvelopeRotationActivationService {

    private final FileKeyEnvelopeMapper envelopeMapper;

    /**
     * Atomically supersedes the still-active source and activates one verified candidate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String activateVerifiedCandidate(Long tenantId,
                                            Long sourceEnvelopeId,
                                            Long candidateEnvelopeId,
                                            WrappingKeyReference target,
                                            Integer targetLogicalKeyVersion) {
        FileKeyEnvelope source = envelopeMapper.selectEnvelopeForUpdate(tenantId, sourceEnvelopeId);
        FileKeyEnvelope candidate = envelopeMapper.selectEnvelopeForUpdate(tenantId, candidateEnvelopeId);
        if (source == null || candidate == null || !sameRecipient(source, candidate)
                || !matchesTarget(candidate, target, targetLogicalKeyVersion)) {
            retirePendingCandidate(tenantId, candidate);
            return "SKIPPED_SOURCE_CHANGED";
        }
        if (FileKeyEnvelopeService.STATUS_REVOKED.equals(source.getStatus())) {
            retirePendingCandidate(tenantId, candidate);
            return "SKIPPED_REVOKED";
        }
        if (!FileKeyEnvelopeService.STATUS_ACTIVE.equals(source.getStatus())) {
            if (FileKeyEnvelopeService.STATUS_ACTIVE.equals(candidate.getStatus())) {
                return "SUCCEEDED";
            }
            retirePendingCandidate(tenantId, candidate);
            return "SKIPPED_SOURCE_CHANGED";
        }
        if (!FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION.equals(candidate.getStatus())) {
            return "SKIPPED_SOURCE_CHANGED";
        }
        int sourceUpdated = envelopeMapper.compareAndSetStatus(
                tenantId, sourceEnvelopeId,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED);
        if (sourceUpdated != 1) {
            throw new IllegalStateException("rotation source authority changed");
        }
        int candidateUpdated = envelopeMapper.compareAndSetStatus(
                tenantId, candidateEnvelopeId,
                FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                FileKeyEnvelopeService.STATUS_ACTIVE);
        if (candidateUpdated != 1) {
            throw new IllegalStateException("verified rotation candidate activation failed");
        }
        return "SUCCEEDED";
    }

    /**
     * Prevents an abandoned pending candidate from becoming readable later.
     */
    private void retirePendingCandidate(Long tenantId, FileKeyEnvelope candidate) {
        if (candidate != null && FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION.equals(candidate.getStatus())) {
            envelopeMapper.compareAndSetStatus(
                    tenantId, candidate.getId(),
                    FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                    FileKeyEnvelopeService.STATUS_SUPERSEDED);
        }
    }

    /**
     * Requires the candidate to preserve the exact recipient authorization boundary.
     */
    private boolean sameRecipient(FileKeyEnvelope source, FileKeyEnvelope candidate) {
        return Objects.equals(source.getTenantId(), candidate.getTenantId())
                && Objects.equals(source.getFileId(), candidate.getFileId())
                && Objects.equals(source.getFileHash(), candidate.getFileHash())
                && Objects.equals(source.getRecipientType(), candidate.getRecipientType())
                && Objects.equals(source.getRecipientId(), candidate.getRecipientId());
    }

    /**
     * Requires every persisted target-routing field to match the frozen run snapshot.
     */
    private boolean matchesTarget(FileKeyEnvelope candidate,
                                  WrappingKeyReference target,
                                  Integer targetLogicalKeyVersion) {
        return Objects.equals(candidate.getKeyVersion(), targetLogicalKeyVersion)
                && Objects.equals(candidate.getKmsProvider(), target.providerId())
                && Objects.equals(candidate.getProviderContractVersion(), target.providerContractVersion())
                && Objects.equals(candidate.getKmsKeyId(), target.keyId())
                && Objects.equals(candidate.getProviderKeyVersion(), target.providerKeyVersion())
                && Objects.equals(candidate.getWrappingAlgorithm(), target.wrappingAlgorithm())
                && Objects.equals(candidate.getContextSchema(), target.contextSchema());
    }
}
