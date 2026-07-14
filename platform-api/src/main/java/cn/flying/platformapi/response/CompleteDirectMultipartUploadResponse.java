package cn.flying.platformapi.response;

import java.io.Serializable;
import java.util.List;

/**
 * Response returned after storage validates and promotes direct-uploaded chunks.
 */
public record CompleteDirectMultipartUploadResponse(
        String sessionId,
        String contentHash,
        List<DirectMultipartCompletedPartVO> parts
) implements Serializable {

    /**
     * 兼容旧调用方构造器；新上传完成链路必须返回可信的整体内容哈希。
     *
     * @param sessionId 上传会话 ID
     * @param parts 已完成分片
     */
    public CompleteDirectMultipartUploadResponse(
            String sessionId,
            List<DirectMultipartCompletedPartVO> parts
    ) {
        this(sessionId, null, parts);
    }
}
