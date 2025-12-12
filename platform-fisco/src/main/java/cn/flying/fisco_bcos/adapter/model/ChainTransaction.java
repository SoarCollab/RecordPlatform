package cn.flying.fisco_bcos.adapter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 区块链交易详情
 * 统一封装不同链的交易信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainTransaction {

    /**
     * 交易哈希
     */
    private String hash;

    /**
     * 链 ID
     */
    private String chainId;

    /**
     * 群组 ID (FISCO) 或 null (Besu)
     */
    private String groupId;

    /**
     * 合约 ABI
     */
    private String abi;

    /**
     * 发送者地址
     */
    private String from;

    /**
     * 接收者地址 (合约地址)
     */
    private String to;

    /**
     * 输入数据
     */
    private String input;

    /**
     * 签名
     */
    private String signature;

    /**
     * 导入时间
     */
    private Long importTime;

    /**
     * 区块号
     */
    private Long blockNumber;

    /**
     * Gas 消耗
     */
    private Long gasUsed;

    /**
     * 交易状态
     */
    private boolean success;
}
