package cn.flying.service.attestation;

/**
 * 描述一个 Merkle 叶子与文件版本、manifest 和链记录之间的不可变证据绑定。
 */
public record AttestationLeafEvidence(
        Long fileId,
        Integer fileVersion,
        Long manifestId,
        String evidenceType,
        String evidenceHash,
        String chainRecordId
) {
}
