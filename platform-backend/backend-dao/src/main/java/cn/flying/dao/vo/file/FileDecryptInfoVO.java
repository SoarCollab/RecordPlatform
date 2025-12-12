package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件解密信息响应 VO
 * 包含前端解密所需的初始密钥和元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件解密信息响应类")
public class FileDecryptInfoVO {

    @Schema(description = "初始密钥（最后一个分片的解密密钥，Base64编码）")
    private String initialKey;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件MIME类型")
    private String contentType;

    @Schema(description = "分片数量")
    private Integer chunkCount;

    @Schema(description = "文件哈希")
    private String fileHash;
}
