package cn.flying.platformapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分块信息数据传输对象
 * 表示单个分块的信息
 *
 * @author 王贝强
 * @date 2025-12-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartInfoDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分块编号
     * 从1开始的整数
     */
    private int partNumber;

    /**
     * 分块大小（字节）
     */
    private long partSize;

    /**
     * 分块的ETag
     * MinIO返回的分块标识
     */
    private String etag;

    /**
     * 分块的哈希值
     * SHA-256哈希，用于完整性校验
     */
    private String partHash;

    /**
     * 上传时间戳
     */
    private long uploadTime;

    /**
     * 上传重试次数
     */
    private int retryCount;

    /**
     * 是否上传成功
     */
    private boolean uploaded;

    /**
     * 错误信息（如果上传失败）
     */
    private String errorMessage;
}