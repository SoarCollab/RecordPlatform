package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链交易回执
 * 统一封装不同链的交易结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainReceipt {

    /**
     * 交易哈希
     */
    private String transactionHash;

    /**
     * 文件哈希 (存储操作返回)
     */
    private String fileHash;

    /**
     * 分享码 (分享操作返回)
     */
    private String shareCode;

    /**
     * 区块号
     */
    private Long blockNumber;

    /**
     * Gas 消耗 (仅 EVM 兼容链)
     */
    private Long gasUsed;

    /**
     * 交易是否成功
     */
    private boolean success;

    /**
     * 错误信息 (失败时)
     */
    private String errorMessage;

    /**
     * 创建成功回执
     */
    public static ChainReceipt success(String txHash, String fileHash) {
        return ChainReceipt.builder()
                .transactionHash(txHash)
                .fileHash(fileHash)
                .success(true)
                .build();
    }

    /**
     * 创建失败回执
     */
    public static ChainReceipt failure(String errorMessage) {
        return ChainReceipt.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
