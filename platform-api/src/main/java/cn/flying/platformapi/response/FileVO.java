package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件信息视图对象
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "文件信息")
public class FileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件名称")
    private String fileName;

    @Schema(description = "文件哈希")
    private String fileHash;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "上传时间戳（毫秒）")
    private Long uploadTimestamp;

    @Schema(description = "文件MIME类型")
    private String mimeType;
}
