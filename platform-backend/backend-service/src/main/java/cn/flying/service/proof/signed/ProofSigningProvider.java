package cn.flying.service.proof.signed;

/**
 * Proof bundle 专用签名 provider 边界，可在 P3 替换为 KMS/HSM 实现。
 */
public interface ProofSigningProvider {

    /**
     * 确认全局 proof ZIP 导出开关开启；历史重建不得因此依赖当前私钥。
     */
    void requireExportEnabled();

    /**
     * 返回当前可用于新签发的公开 key 元数据。
     *
     * @return 当前 ACTIVE key
     */
    ProofSigningKeyMetadata currentKey();

    /**
     * 对 canonical manifest 生成 compact JWS，并阻断调用期间发生的 key 漂移。
     *
     * @param manifest canonical manifest bytes
     * @param expectedKey 构建 manifest 时绑定的 key
     * @return compact JWS 与 key 元数据
     */
    ProofSignature sign(byte[] manifest, ProofSigningKeyMetadata expectedKey);

    /**
     * 使用历史 SPKI 验证 compact JWS payload 与 canonical manifest 完全一致。
     *
     * @param manifest canonical manifest bytes
     * @param compactJws compact JWS
     * @param expectedKey 历史 key id/version/SPKI/fingerprint 元数据
     * @return 签名和 payload 是否有效
     */
    boolean verify(byte[] manifest, String compactJws, ProofSigningKeyMetadata expectedKey);
}
