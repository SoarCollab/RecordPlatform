package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 已签发证明使用的历史公开验证密钥材料。
 */
@Schema(description = "证明签名公钥")
public record ProofSigningKeyVO(
        @Schema(description = "签名 key id", requiredMode = Schema.RequiredMode.REQUIRED)
        String keyId,
        @Schema(description = "签名 key 版本", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer keyVersion,
        @Schema(description = "JWS 算法", requiredMode = Schema.RequiredMode.REQUIRED)
        String algorithm,
        @Schema(description = "Base64 编码的 X.509 SPKI 公钥", requiredMode = Schema.RequiredMode.REQUIRED)
        String publicKeySpki,
        @Schema(description = "SPKI SHA-256 指纹", requiredMode = Schema.RequiredMode.REQUIRED)
        String publicKeyFingerprint
) {
}
