package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response after direct upload completion, file registration, and manifest persistence.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "直传上传完成响应")
public class DirectUploadCompleteVO {
    @Schema(description = "客户端会话 ID")
    private String clientId;
    @Schema(description = "文件外部 ID")
    private String fileId;
    @Schema(description = "链上文件哈希")
    private String fileHash;
    @Schema(description = "交易哈希")
    private String transactionHash;
    @Schema(description = "chunk manifest hash")
    private String manifestHash;
    @Schema(description = "完成状态")
    private String status;
}
