package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链存储文件响应 DTO
 */
public record StoreFileResponse(
        String transactionHash,
        String fileHash
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
