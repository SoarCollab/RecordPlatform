package cn.flying.platformapi.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分享信息视图对象
 */
@Schema(description = "分享信息")
public record SharingVO(
        @Schema(description = "上传者")
        String uploader,

        @Schema(description = "文件哈希列表")
        List<String> fileHashList,

        @Schema(description = "分享码")
        String shareCode,

        @Schema(description = "最大访问次数")
        Integer maxAccesses,

        @Schema(description = "剩余访问次数")
        Integer remainingAccesses,

        @Schema(description = "过期时间戳（毫秒，取消分享时为 -1）")
        Long expirationTime,

        @JsonProperty("isValid")
        @Schema(description = "是否有效（未被取消）")
        Boolean isValid
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
