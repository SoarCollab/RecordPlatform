package cn.flying.dao.vo.file;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: /upload/resume 接口响应 DTO
 * @author: flyingcoding
 * @create: 2025-04-01 13:43
 */
@Getter
@Setter
@NoArgsConstructor
public class ResumeUploadVO {
    private List<Integer> processedChunks; // 已处理的分片序号列表
    private int totalChunks;

    public ResumeUploadVO(boolean success, String message, List<Integer> processedChunks, int totalChunks) {
        this.processedChunks = processedChunks;
        this.totalChunks = totalChunks;
    }
}