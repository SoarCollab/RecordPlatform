package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链交易信息视图对象
 * 用于返回区块链交易的详细信息
 * 包含交易哈希、区块链网络信息、合约调用详情等
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交易信息")
public class TransactionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 交易哈希值
     * 区块链交易的唯一标识符
     */
    @Schema(description = "交易哈希")
    private String transactionHash;
    
    /**
     * 区块链网络ID
     * 标识交易所在的区块链网络
     */
    @Schema(description = "区块链id")
    private String chainId;
    
    /**
     * 群组ID
     * FISCO BCOS 中的群组标识
     */
    @Schema(description = "群组id")
    private String groupId;
    
    /**
     * 智能合约ABI
     * 合约应用程序二进制接口定义
     */
    @Schema(description = "合约abi")
    private String contractABI;
    
    /**
     * 交易发起方地址
     * 发起此次交易的账户地址
     */
    @Schema(description = "合约发起方")
    private String From;
    
    /**
     * 交易接收方地址
     * 接收此次交易的合约或账户地址
     */
    @Schema(description = "合约接收方")
    private String To;
    
    /**
     * 交易输入参数
     * 调用合约方法时传入的参数数据
     */
    @Schema(description = "交易输入参数")
    private String input;
    
    /**
     * 交易数字签名
     * 用于验证交易发起方身份的数字签名
     */
    @Schema(description = "交易签名")
    private String signature;
    
    /**
     * 交易时间戳
     * 交易被打包到区块的时间
     */
    @Schema(description = "交易时间戳")
    private Long timestamp;
}