package cn.flying.platformapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 分块上传数据传输对象
 * 用于在服务间传递分块上传相关信息
 *
 * @author 王贝强
 * @date 2025-12-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultipartUploadDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上传会话ID
     * MinIO返回的uploadId，用于标识一个分块上传会话
     */
    private String uploadId;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 文件哈希值
     * 完整文件的SHA-256哈希，用于完整性校验
     */
    private String fileHash;

    /**
     * 文件总大小（字节）
     */
    private long totalSize;

    /**
     * 分块大小（字节）
     * 建议的分块大小，通常为5MB-10MB
     */
    private int chunkSize;

    /**
     * 总分块数
     */
    private int totalParts;

    /**
     * 存储桶名称
     */
    private String bucketName;

    /**
     * 对象名称（存储路径）
     */
    private String objectName;

    /**
     * 文件元数据
     * 包含文件类型、创建时间、用户信息等
     */
    private Map<String, String> metadata;

    /**
     * 创建时间戳
     */
    private long createTime;

    /**
     * 最后更新时间戳
     */
    private long updateTime;

    /**
     * 上传状态
     * INITIALIZING, UPLOADING, COMPLETED, ABORTED
     */
    private String status;

    /**
     * 物理节点名称
     */
    private String nodeName;

    /**
     * 逻辑节点名称
     */
    private String logicNodeName;

    /**
     * 已上传的分块信息列表
     */
    private List<Map<String, Object>> uploadedParts;

    /**
     * 已上传的总大小（字节）
     */
    private long uploadedSize;
}