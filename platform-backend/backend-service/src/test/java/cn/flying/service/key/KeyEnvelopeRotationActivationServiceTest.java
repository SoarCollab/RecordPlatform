package cn.flying.service.key;

import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the short transaction that transfers recipient authority to a verified candidate.
 */
@ExtendWith(MockitoExtension.class)
class KeyEnvelopeRotationActivationServiceTest {

    private static final Long TENANT_ID = 11L;
    private static final Long SOURCE_ID = 101L;
    private static final Long CANDIDATE_ID = 201L;

    @Mock
    private FileKeyEnvelopeMapper envelopeMapper;

    private KeyEnvelopeRotationActivationService service;
    private WrappingKeyReference target;

    /**
     * Creates one exact source/candidate target identity for each authority-boundary test.
     */
    @BeforeEach
    void setUp() {
        service = new KeyEnvelopeRotationActivationService(envelopeMapper);
        target = new WrappingKeyReference(
                "vault-transit", 1, "tenant-key", "7", "VAULT-TRANSIT", "external-v2");
    }

    /**
     * Proves source supersession occurs before candidate activation and both writes are fenced.
     */
    @Test
    void shouldActivateVerifiedCandidateWithOrderedCasWrites() {
        FileKeyEnvelope source = source(FileKeyEnvelopeService.STATUS_ACTIVE);
        FileKeyEnvelope candidate = candidate(FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, SOURCE_ID)).thenReturn(source);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, CANDIDATE_ID)).thenReturn(candidate);
        when(envelopeMapper.compareAndSetStatus(
                TENANT_ID, SOURCE_ID,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED)).thenReturn(1);
        when(envelopeMapper.compareAndSetStatus(
                TENANT_ID, CANDIDATE_ID,
                FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                FileKeyEnvelopeService.STATUS_ACTIVE)).thenReturn(1);

        assertThat(service.activateVerifiedCandidate(
                TENANT_ID, SOURCE_ID, CANDIDATE_ID, target, 2)).isEqualTo("SUCCEEDED");

        InOrder ordered = inOrder(envelopeMapper);
        ordered.verify(envelopeMapper).compareAndSetStatus(
                TENANT_ID, SOURCE_ID,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED);
        ordered.verify(envelopeMapper).compareAndSetStatus(
                TENANT_ID, CANDIDATE_ID,
                FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                FileKeyEnvelopeService.STATUS_ACTIVE);
    }

    /**
     * Proves a share revocation that wins the row lock cannot be undone by rotation.
     */
    @Test
    void shouldRetireCandidateWhenSourceWasRevoked() {
        FileKeyEnvelope source = source(FileKeyEnvelopeService.STATUS_REVOKED);
        FileKeyEnvelope candidate = candidate(FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, SOURCE_ID)).thenReturn(source);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, CANDIDATE_ID)).thenReturn(candidate);
        when(envelopeMapper.compareAndSetStatus(
                TENANT_ID, CANDIDATE_ID,
                FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                FileKeyEnvelopeService.STATUS_SUPERSEDED)).thenReturn(1);

        assertThat(service.activateVerifiedCandidate(
                TENANT_ID, SOURCE_ID, CANDIDATE_ID, target, 2)).isEqualTo("SKIPPED_REVOKED");

        verify(envelopeMapper, never()).compareAndSetStatus(
                TENANT_ID, SOURCE_ID,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED);
    }

    /**
     * Proves a crash-replayed item observes its already-active deterministic candidate as success.
     */
    @Test
    void shouldRecoverAlreadyActivatedCandidateIdempotently() {
        FileKeyEnvelope source = source(FileKeyEnvelopeService.STATUS_SUPERSEDED);
        FileKeyEnvelope candidate = candidate(FileKeyEnvelopeService.STATUS_ACTIVE);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, SOURCE_ID)).thenReturn(source);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, CANDIDATE_ID)).thenReturn(candidate);

        assertThat(service.activateVerifiedCandidate(
                TENANT_ID, SOURCE_ID, CANDIDATE_ID, target, 2)).isEqualTo("SUCCEEDED");

        verify(envelopeMapper, never()).compareAndSetStatus(
                TENANT_ID, SOURCE_ID,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED);
    }

    /**
     * Proves a failed candidate activation rolls back by surfacing an exception to the transaction interceptor.
     */
    @Test
    void shouldFailTransactionWhenCandidateCasIsLost() {
        FileKeyEnvelope source = source(FileKeyEnvelopeService.STATUS_ACTIVE);
        FileKeyEnvelope candidate = candidate(FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, SOURCE_ID)).thenReturn(source);
        when(envelopeMapper.selectEnvelopeForUpdate(TENANT_ID, CANDIDATE_ID)).thenReturn(candidate);
        when(envelopeMapper.compareAndSetStatus(
                TENANT_ID, SOURCE_ID,
                FileKeyEnvelopeService.STATUS_ACTIVE,
                FileKeyEnvelopeService.STATUS_SUPERSEDED)).thenReturn(1);
        when(envelopeMapper.compareAndSetStatus(
                TENANT_ID, CANDIDATE_ID,
                FileKeyEnvelopeService.STATUS_PENDING_VERIFICATION,
                FileKeyEnvelopeService.STATUS_ACTIVE)).thenReturn(0);

        assertThatThrownBy(() -> service.activateVerifiedCandidate(
                TENANT_ID, SOURCE_ID, CANDIDATE_ID, target, 2))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Builds an exact source recipient row with a caller-selected lifecycle state.
     */
    private FileKeyEnvelope source(String status) {
        return new FileKeyEnvelope()
                .setId(SOURCE_ID)
                .setTenantId(TENANT_ID)
                .setFileId(31L)
                .setFileHash("sha256:file")
                .setRecipientType(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE)
                .setRecipientId(41L)
                .setStatus(status);
    }

    /**
     * Builds a candidate that preserves the recipient boundary and frozen target reference.
     */
    private FileKeyEnvelope candidate(String status) {
        return new FileKeyEnvelope()
                .setId(CANDIDATE_ID)
                .setTenantId(TENANT_ID)
                .setFileId(31L)
                .setFileHash("sha256:file")
                .setRecipientType(FileKeyEnvelopeService.RECIPIENT_TYPE_SHARE)
                .setRecipientId(41L)
                .setKeyVersion(2)
                .setKmsProvider(target.providerId())
                .setProviderContractVersion(target.providerContractVersion())
                .setKmsKeyId(target.keyId())
                .setProviderKeyVersion(target.providerKeyVersion())
                .setWrappingAlgorithm(target.wrappingAlgorithm())
                .setContextSchema(target.contextSchema())
                .setStatus(status);
    }
}
