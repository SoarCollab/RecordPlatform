package cn.flying.platformapi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链存储文件响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreFileResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 交易哈希
     */
    private String transactionHash;

    /**
     * 文件哈希
     */
    private String fileHash;
}
