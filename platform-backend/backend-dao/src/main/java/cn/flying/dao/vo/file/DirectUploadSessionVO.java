package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Response for a direct multipart upload session.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "直传上传会话响应")
public class DirectUploadSessionVO {
    @Schema(description = "客户端会话 ID")
    private String clientId;
    @Schema(description = "分片大小")
    private int chunkSize;
    @Schema(description = "分片总数")
    private int totalChunks;
    @Schema(description = "是否恢复既有会话")
    private boolean resumed;
    @Schema(description = "manifest schema id")
    private String manifestSchemaId;
    @Schema(description = "分片预签名 URL 列表")
    private List<DirectUploadPartUrlVO> parts;
}
