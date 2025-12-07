package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件详情响应对象。
 * 包含区块链和存储系统中的文件元数据。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上传者标识
     */
    private String uploader;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件参数（自定义元数据）
     */
    private String param;

    /**
     * 文件内容映射（MinIO 存储路径 JSON）
     */
    private String content;

    /**
     * 文件哈希（区块链唯一标识）
     */
    private String fileHash;

    /**
     * 上传时间（格式化字符串，兼容旧版本）
     */
    private String uploadTime;

    /**
     * 上传时间戳（毫秒）
     */
    private Long uploadTimestamp;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MIME 类型
     */
    private String mimeType;
} 