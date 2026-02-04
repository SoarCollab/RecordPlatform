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
        String fileHash
) {

    public String getInitialKey() {
        return initialKey;
    }

    public String getFileName() {
        return fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getContentType() {
        return contentType;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public String getFileHash() {
        return fileHash;
    }
}
