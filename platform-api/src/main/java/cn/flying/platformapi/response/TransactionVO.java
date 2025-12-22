package cn.flying.platformapi.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "交易信息")
public class TransactionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "交易哈希")
    private String transactionHash;
    @Schema(description = "区块链id")
    private String chainId;
    @Schema(description = "群组id")
    private String groupId;
    @Schema(description = "合约abi")
    private String contractABI;
    @Schema(description = "合约发起方")
    @JsonProperty("from")
    private String fromAddress;
    @Schema(description = "合约接收方")
    @JsonProperty("to")
    private String toAddress;
    @Schema(description = "交易输入参数")
    private String input;
    @Schema(description = "交易签名")
    private String signature;
    @Schema(description = "区块高度")
    private String blockNumber;
    @Schema(description = "交易时间戳")
    private Long timestamp;
} 