package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request body for completing a direct multipart upload session.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "直传上传完成请求")
public class DirectUploadCompleteRequest {
    @NotEmpty
    @Valid
    @Schema(description = "分片完成元数据")
    private List<DirectUploadCompletePartRequest> parts;
}
