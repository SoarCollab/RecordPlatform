package cn.flying.service.key;

/**
 * 下载 grant 绑定的精确信封路由快照，不包含密钥标识或密钥材料。
 */
public record FileKeyGrantEnvelopeBinding(
        Long envelopeId,
        Long tenantId,
        Long fileId,
        Integer fileVersion,
        String fileHash,
        String recipientType,
        Long recipientId,
        Integer keyVersion,
        String algorithmSuite,
        String signatureSuite,
        String kemSuite,
        String proofSuite,
        String encryptionAlgorithm,
        String kmsProvider,
        Integer providerContractVersion,
        String providerKeyVersion,
        boolean legacyPlaintextAtRest
) {
}
