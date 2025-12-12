package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链文件详情
 * 包含完整的文件元数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainFileDetail {

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 上传者标识
     */
    private String uploader;

    /**
     * 文件内容 (分片路径 JSON)
     */
    private String content;

    /**
     * 自定义参数 (JSON)
     */
    private String param;

    /**
     * 文件哈希
     */
    private String fileHash;

    /**
     * 上传时间戳 (毫秒)
     */
    private Long uploadTimestamp;

    /**
     * 格式化的上传时间
     */
    private String uploadTimeFormatted;
}
