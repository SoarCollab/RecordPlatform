package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链存储文件请求 DTO
 */
public record StoreFileRequest(
        String uploader,
        String fileName,
        String param,
        String content
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
