package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文件解密信息响应 VO
 * 包含前端解密所需的初始密钥和元数据
 */
@Schema(description = "文件解密信息响应类")
public record FileDecryptInfoVO(
        @Schema(description = "初始密钥（最后一个分片的解密密钥，Base64编码）")
        String initialKey,
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
        this(initialKey, fileName, fileSize, contentType, chunkCount, fileHash, null);
    }
}
