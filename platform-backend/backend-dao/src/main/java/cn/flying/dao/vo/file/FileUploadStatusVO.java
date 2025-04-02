package cn.flying.dao.vo.file;

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
public class FileUploadStatusVO {
    private String fileName;
    private long fileSize;
    private String sessionId;
    private String clientId;
    private boolean paused;
    // 例如 "UPLOADING", "PAUSED", "PROCESSING_COMPLETE", "NOT_FOUND"
    private String status;
    // 总体进度百分比
    private int progress;
    // 已处理的分片序号列表
    private List<Integer> processedChunks;
    // 已处理分片数量
    private int processedChunkCount;
    // 总分片数量
    private int totalChunks;

}
