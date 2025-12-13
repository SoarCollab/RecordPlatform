package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分享信息视图对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "分享信息")
public class SharingVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "上传者")
    private String uploader;

    @Schema(description = "文件哈希列表")
    private List<String> fileHashList;

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "最大访问次数")
    private Integer maxAccesses;

    @Schema(description = "剩余访问次数")
    private Integer remainingAccesses;

    @Schema(description = "过期时间戳（毫秒）")
    private Long expirationTime;

    @Schema(description = "是否有效（未被取消）")
    private Boolean isValid;
}
