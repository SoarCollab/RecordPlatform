package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文件解密信息响应 VO，默认仅返回短期 grant 与非秘密元数据。
 */
@Schema(description = "文件解密信息响应类")
public record FileDecryptInfoVO(
        @Schema(description = "仅 plaintext-v0 兼容模式返回的初始密钥；grant-v1 下为空")
        String initialKey,
        @Schema(description = "grant-v1 短期密钥授权；未加密文件或 plaintext-v0 下为空")
        DownloadKeyGrantVO keyGrant,
        @Schema(description = "文件名")
        String fileName,
        @Schema(description = "文件大小（字节）")
        Long fileSize,
        @Schema(description = "文件MIME类型")
        String contentType,
        @Schema(description = "分片数量")
        Integer chunkCount,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "原始上传分片大小；历史文件可能为空")
        Long chunkSize
) {

    /**
     * 保留历史六参数构造调用，旧文件没有可用的分片大小证据时返回 null。
     */
    public FileDecryptInfoVO(
            String initialKey,
            String fileName,
            Long fileSize,
            String contentType,
            Integer chunkCount,
            String fileHash
    ) {
        this(initialKey, null, fileName, fileSize, contentType, chunkCount, fileHash, null);
    }

    /**
     * 保留历史七参数构造调用，并让旧调用显式不携带 grant。
     */
    public FileDecryptInfoVO(
            String initialKey,
            String fileName,
            Long fileSize,
            String contentType,
            Integer chunkCount,
            String fileHash,
            Long chunkSize
    ) {
        this(initialKey, null, fileName, fileSize, contentType, chunkCount, fileHash, chunkSize);
    }
}
