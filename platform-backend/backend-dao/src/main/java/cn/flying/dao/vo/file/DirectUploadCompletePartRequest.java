package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Completion metadata returned by object storage for one direct-upload chunk.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "直传分片完成请求")
public class DirectUploadCompletePartRequest {
    @Min(0)
    @Schema(description = "分片索引，从 0 开始")
    private int index;

    @Schema(description = "对象存储返回的 ETag")
    private String eTag;
}
