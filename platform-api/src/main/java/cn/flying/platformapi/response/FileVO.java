package cn.flying.platformapi.response;

import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件信息视图对象
 */
@Schema(description = "文件信息")
public record FileVO(
        @Schema(description = "文件名称")
        String fileName,

        @Schema(description = "文件哈希")
        String fileHash,

        @Schema(description = "文件大小（字节）")
        Long fileSize,

        @Schema(description = "上传时间戳（毫秒）")
        Long uploadTimestamp,

        @Schema(description = "文件MIME类型")
        String mimeType
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
