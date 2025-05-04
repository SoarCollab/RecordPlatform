package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * /upload/start 接口响应 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件上传开始响应类")
public class StartUploadVO {
    @Schema(description = "客户端Id")
    private String clientId;
    @Schema(description = "分片大小")
    private int chunkSize;
    @Schema(description = "总分片数量")
    private int totalChunks;
    @Schema(description = "是否为单分片文件")
    private boolean singleChunk; // 是否为单分片文件
    @Schema(description = "已上传原始分片数量")
    private List<Integer> processedChunks; // 更准确的命名：已处理的分片
    @Schema(description = "是否为恢复的会话")
    private boolean resumed; // 标记是否为恢复的会话
}