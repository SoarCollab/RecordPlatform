package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @program: RecordPlatform
 * @description: /upload/progress 接口响应 DTO
 * @author: flyingcoding
 * @create: 2025-04-01 13:45
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "文件上传进度类")
public class ProgressVO {
    @Schema(description = "是否成功")
    private int progress; // 总体进度百分比
    @Schema(description = "上传进度百分比")
    private int uploadProgress; // 原始分片上传进度百分比
    @Schema(description = "处理进度百分比")
    private int processProgress; // 分片处理进度百分比
    @Schema(description = "已上传原始分片数量")
    private int uploadedChunkCount; // 已上传原始分片数量
    @Schema(description = "已处理分片数量")
    private int processedChunkCount; // 已处理分片数量
    @Schema(description = "总分片数量")
    private int totalChunks; // 总分片数量
    @Schema(description = "客户端ID")
    private String clientId;

    public ProgressVO(int progress, int uploadProgress,
                      int processProgress, int uploadedChunkCount,
                      int processedChunkCount, int totalChunks,
                      String clientId) {
        this.progress = progress;
        this.uploadProgress = uploadProgress;
        this.processProgress = processProgress;
        this.uploadedChunkCount = uploadedChunkCount;
        this.processedChunkCount = processedChunkCount;
        this.totalChunks = totalChunks;
        this.clientId = clientId;
    }
}
