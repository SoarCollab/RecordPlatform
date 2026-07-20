package cn.flying.dao.vo.file;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @NotNull
    @Min(0)
    @Schema(description = "分片索引，从 0 开始", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer index;

    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = "^[\\x21-\\x7E]{1,255}$", message = "eTag 只能包含可见 ASCII 字符")
    private String eTag;

    /**
     * 返回对象存储响应的 ETag，并固定公开 JSON 属性名为 eTag。
     *
     * @return 对象存储 ETag
     */
    @JsonProperty("eTag")
    @Schema(
            description = "对象存储返回的 ETag",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1,
            maxLength = 255,
            pattern = "^[\\x21-\\x7E]{1,255}$"
    )
    public String getETag() {
        return eTag;
    }

    /**
     * 接收规范 eTag 属性，同时兼容旧生成客户端使用的 etag。
     *
     * @param eTag 对象存储 ETag
     */
    @JsonProperty("eTag")
    @JsonAlias("etag")
    public void setETag(String eTag) {
        this.eTag = eTag;
    }
}
