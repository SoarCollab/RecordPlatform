package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request body for creating a direct multipart upload session.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "直传上传会话创建请求")
public class DirectUploadSessionRequest {
    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = "^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$")
    @Schema(description = "文件名")
    private String fileName;

    @Min(1)
    @Max(4294967296L)
    @Schema(description = "文件大小")
    private long fileSize;

    @NotBlank
    @Schema(description = "文件类型")
    private String contentType;

    @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$", message = "clientId 格式无效")
    @Schema(description = "客户端会话 ID")
    private String clientId;

    @Min(1)
    @Max(83886080)
    @Schema(description = "分片大小")
    private int chunkSize;

    @Min(1)
    @Max(10000)
    @Schema(description = "分片总数")
    private int totalChunks;

    @Schema(description = "目标文件ID（可选）")
    private String fileId;

    @NotEmpty
    @Valid
    @Schema(description = "分片元数据")
    private List<DirectUploadPartRequest> parts;
}
