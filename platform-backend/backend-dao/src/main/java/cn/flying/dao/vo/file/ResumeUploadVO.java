package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: /upload/resume 接口响应 DTO
 * @author flyingcoding
 * @create: 2025-04-01 13:43
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "文件上传进度类")
public class ResumeUploadVO {
    @Schema(description = "已处理的分片序号列表")
    private List<Integer> processedChunks; // 已处理的分片序号列表
    @Schema(description = "总分片数量")
    private int totalChunks;

    public ResumeUploadVO(List<Integer> processedChunks, int totalChunks) {
        this.processedChunks = processedChunks;
        this.totalChunks = totalChunks;
    }
}