package org.fisco.fisco_bcos.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionVO {
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