package cn.flying.platformapi.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.dubbo.remoting.http12.rest.Schema;

import java.io.Serial;
import java.io.Serializable;

@Schema(description = "交易信息")
public record TransactionVO(
        @Schema(description = "交易哈希")
        String transactionHash,

        @Schema(description = "区块链id")
        String chainId,

        @Schema(description = "群组id")
        String groupId,

        @Schema(description = "合约abi")
        String contractABI,

        @JsonProperty("from")
        @Schema(description = "合约发起方")
        String fromAddress,

        @JsonProperty("to")
        @Schema(description = "合约接收方")
        String toAddress,

        @Schema(description = "交易输入参数")
        String input,

        @Schema(description = "交易签名")
        String signature,

        @Schema(description = "区块高度")
        String blockNumber,

        @Schema(description = "交易时间戳")
        Long timestamp
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
