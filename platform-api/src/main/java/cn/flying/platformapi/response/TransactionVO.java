package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String transactionHash;
    private String blockHash;
    private String blockNumber;
    private String from;
    private String to;
    private String input;
    private String output;
    private Long gasLimit;
    private String timestamp;
} 