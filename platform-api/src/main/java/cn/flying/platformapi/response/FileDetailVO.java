package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件详情响应对象。
 * 包含区块链和存储系统中的文件元数据。
 */
public record FileDetailVO(
        String uploader,
        String fileName,
        String param,
        String content,
        String fileHash,
        String uploadTime,
        Long uploadTimestamp,
        Long fileSize,
        String mimeType
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
