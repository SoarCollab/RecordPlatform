package cn.flying.dao.vo.file;

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
public class ProgressVO {
    private int progress; // 总体进度百分比
    private int uploadProgress; // 原始分片上传进度百分比
    private int processProgress; // 分片处理进度百分比
    private int uploadedChunkCount; // 已上传原始分片数量
    private int processedChunkCount; // 已处理分片数量
    private int totalChunks; // 总分片数量
    private String sessionId;

    public ProgressVO(boolean success, String message, int progress, int uploadProgress,
                      int processProgress, int uploadedChunkCount, int processedChunkCount,
                      int totalChunks, String sessionId) {
        this.progress = progress;
        this.uploadProgress = uploadProgress;
        this.processProgress = processProgress;
        this.uploadedChunkCount = uploadedChunkCount;
        this.processedChunkCount = processedChunkCount;
        this.totalChunks = totalChunks;
        this.sessionId = sessionId;
    }
}
