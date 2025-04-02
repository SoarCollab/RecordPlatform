package cn.flying.dao.vo.file;

/**
 * @program: RecordPlatform
 * @description:
 * @author: flyingcoding
 * @create: 2025-04-01 13:41
 */
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
public class StartUploadVO {
    private String sessionId;
    private String clientId;
    private int chunkSize;
    private int totalChunks;
    private boolean singleChunk; // 是否为单分片文件
    private List<Integer> processedChunks; // 更准确的命名：已处理的分片
    private boolean resumed; // 标记是否为恢复的会话
}