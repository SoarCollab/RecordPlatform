package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: /upload/check 接口响应 DTO
 * @author: flyingcoding
 * @create: 2025-04-01 13:44
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件上传状态类")
public class FileUploadStatusVO {
    @Schema(description = "文件名")
    private String fileName;
    @Schema(description = "文件大小")
    private long fileSize;
    @Schema(description = "会话ID")
    private String sessionId;
    @Schema(description = "客户端ID")
    private String clientId;
    @Schema(description = "是否暂停")
    private boolean paused;
    // 例如 "UPLOADING", "PAUSED", "PROCESSING_COMPLETE", "NOT_FOUND"
    @Schema(description = "上传状态：UPLOADING -> 上传中, PAUSED -> 暂停, PROCESSING_COMPLETE -> 处理完成")
    private String status;
    // 总体进度百分比
    @Schema(description = "上传进度百分比")
    private int progress;
    // 已处理的分片序号列表
    @Schema(description = "已处理的分片序号列表")
    private List<Integer> processedChunks;
    // 已处理分片数量
    @Schema(description = "已处理分片数量")
    private int processedChunkCount;
    // 总分片数量
    @Schema(description = "总分片数量")
    private int totalChunks;

}
