package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 下载端用于选择并验证版本化加密格式的描述信息。
 */
@Schema(description = "文件下载加密格式描述")
public record FileDownloadEncryptionVO(
        @Schema(description = "加密格式版本：0=NONE，1=legacy，2=framed AEAD")
        Integer formatVersion,
        @Schema(description = "算法套件")
        String algorithmSuite,
        @Schema(description = "文件级 nonce，Base64URL 无填充")
        String fileNonce,
        @Schema(description = "单帧明文大小")
        Integer framePlainSize,
        @Schema(description = "帧密钥派生算法")
        String keyDerivation,
        @Schema(description = "帧 nonce 派生算法")
        String nonceDerivation,
        @Schema(description = "AAD 字节合同标识")
        String aadSchema,
        @Schema(description = "AEAD 标签字节数")
        Integer tagSize
) {
}
