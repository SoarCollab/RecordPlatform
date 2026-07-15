package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.crypto.CanonicalJson;
import cn.flying.verifier.crypto.ProofHashes;
import org.springframework.stereotype.Component;

/**
 * Signed proof 专用 canonical JSON 与 SHA-256 实现。
 */
@Component
public class ProofCanonicalizer {

    public static final String HASH_ALGORITHM = ProofHashes.HASH_ALGORITHM;
    public static final String HASH_PREFIX = ProofHashes.HASH_PREFIX;

    private final CanonicalJson canonicalJson = new CanonicalJson();

    /**
     * 把受信任模型序列化为属性和 map key 稳定排序的 UTF-8 JSON。
     *
     * @param value 受信任证明模型
     * @return canonical UTF-8 bytes
     */
    public byte[] canonicalBytes(Object value) {
        try {
            return canonicalJson.canonicalBytes(value);
        } catch (IllegalArgumentException e) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "证明包 canonical JSON 生成失败");
        }
    }

    /**
     * 解析已持久化的 canonical manifest，供历史 proof 使用原签发外部标识重建。
     *
     * @param value 已签发 canonical manifest JSON
     * @return 结构化 manifest
     */
    public SignedProofBundleModel.Manifest parseManifest(String value) {
        if (value == null || value.isBlank()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 不可解析");
        }
        try {
            return canonicalJson.read(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    SignedProofBundleModel.Manifest.class);
        } catch (IllegalArgumentException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 不可解析");
        }
    }

    /**
     * 计算带 sha256 前缀的小写十六进制摘要。
     *
     * @param value 输入字节
     * @return 规范 SHA-256
     */
    public String sha256(byte[] value) {
        return ProofHashes.sha256(value);
    }

    /**
     * 计算 UTF-8 文本的规范摘要。
     *
     * @param value 文本
     * @return 规范 SHA-256
     */
    public String sha256(String value) {
        return ProofHashes.sha256(value);
    }
}
