package cn.flying.fisco_bcos.registry;

import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 通过 FISCO SDK 或 Web3j RPC 严格验证部署交易回执。
 */
@Component
public class RpcContractDeploymentReceiptProbe implements ContractDeploymentReceiptProbe {

    private static final Pattern TRANSACTION_HASH_PATTERN =
            Pattern.compile("(?i)0x[0-9a-f]{64}");
    private static final Pattern ADDRESS_PATTERN =
            Pattern.compile("(?i)0x[0-9a-f]{40}");
    private static final String ZERO_ADDRESS =
            "0x0000000000000000000000000000000000000000";

    /**
     * 查询 FISCO 回执，要求 RPC 成功、status=0 且 tx/address/block 完整一致。
     */
    @Override
    public DeploymentReceipt inspectFisco(Client client, String transactionHash) {
        if (client == null) {
            throw new IllegalArgumentException("FISCO receipt client must not be null");
        }
        String requestedHash = normalizeTransactionHash(
                transactionHash,
                "configured FISCO deployment transaction hash");
        try {
            BcosTransactionReceipt response = client.getTransactionReceipt(
                    requestedHash,
                    false);
            if (response == null || response.hasError()) {
                throw new IllegalStateException(
                        "FISCO deployment receipt is unavailable for " + requestedHash);
            }
            org.fisco.bcos.sdk.v3.model.TransactionReceipt receipt =
                    response.getTransactionReceipt();
            if (receipt == null) {
                throw new IllegalStateException(
                        "FISCO deployment receipt is unavailable for " + requestedHash);
            }
            if (receipt.getStatus() != 0) {
                throw new IllegalStateException(
                        "FISCO deployment receipt is not successful for " + requestedHash
                                + ": status=" + receipt.getStatus());
            }
            return requireReceiptFields(
                    requestedHash,
                    receipt.getTransactionHash(),
                    receipt.getContractAddress(),
                    receipt.getBlockNumber(),
                    "FISCO");
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "Cannot verify FISCO deployment receipt for " + requestedHash,
                    e);
        }
    }

    /**
     * 查询 Besu 回执，要求 RPC 成功、显式 status=1 且 tx/address/block 完整一致。
     */
    @Override
    public DeploymentReceipt inspectBesu(Web3j web3j, String transactionHash) {
        if (web3j == null) {
            throw new IllegalArgumentException("Besu receipt client must not be null");
        }
        String requestedHash = normalizeTransactionHash(
                transactionHash,
                "configured Besu deployment transaction hash");
        try {
            EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(
                            requestedHash)
                    .send();
            if (response == null || response.hasError()) {
                throw new IllegalStateException(
                        "Besu deployment receipt RPC failed for " + requestedHash);
            }
            org.web3j.protocol.core.methods.response.TransactionReceipt receipt =
                    response.getTransactionReceipt()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Besu deployment receipt is unavailable for "
                                            + requestedHash));
            String rawStatus = receipt.getStatus();
            if (rawStatus == null || !BigInteger.ONE.equals(Numeric.decodeQuantity(rawStatus))) {
                throw new IllegalStateException(
                        "Besu deployment receipt is not explicitly successful for "
                                + requestedHash + ": status=" + rawStatus);
            }
            return requireReceiptFields(
                    requestedHash,
                    receipt.getTransactionHash(),
                    receipt.getContractAddress(),
                    parseBesuBlockNumber(receipt.getBlockNumberRaw(), requestedHash),
                    "Besu");
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot verify Besu deployment receipt for " + requestedHash,
                    e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "Cannot verify Besu deployment receipt for " + requestedHash,
                    e);
        }
    }

    /**
     * 严格解析 Besu 十六进制区块数量，拒绝缺失、负数和非规范值。
     */
    private BigInteger parseBesuBlockNumber(String rawBlockNumber, String transactionHash) {
        if (rawBlockNumber == null) {
            throw new IllegalStateException(
                    "Besu deployment receipt block number is missing for " + transactionHash);
        }
        BigInteger blockNumber = Numeric.decodeQuantity(rawBlockNumber);
        if (blockNumber.signum() < 0) {
            throw new IllegalStateException(
                    "Besu deployment receipt block number is invalid for " + transactionHash);
        }
        return blockNumber;
    }

    /**
     * 校验回执的交易哈希、非零合约地址和可表示的非负区块号。
     */
    private DeploymentReceipt requireReceiptFields(
            String requestedHash,
            String receiptHash,
            String contractAddress,
            BigInteger blockNumber,
            String chainName
    ) {
        String normalizedReceiptHash = normalizeTransactionHash(
                receiptHash,
                chainName + " receipt transaction hash");
        if (!requestedHash.equals(normalizedReceiptHash)) {
            throw new IllegalStateException(
                    chainName + " deployment receipt transaction hash mismatch: requested="
                            + requestedHash + ", actual=" + normalizedReceiptHash);
        }
        String normalizedAddress = normalizeAddress(contractAddress, chainName);
        if (blockNumber == null
                || blockNumber.signum() < 0
                || blockNumber.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalStateException(
                    chainName + " deployment receipt block number is invalid for "
                            + requestedHash);
        }
        return new DeploymentReceipt(
                normalizedReceiptHash,
                normalizedAddress,
                blockNumber.longValueExact());
    }

    /**
     * 规范化严格的 32 字节交易哈希。
     */
    private String normalizeTransactionHash(String value, String fieldName) {
        if (value == null || !TRANSACTION_HASH_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException("Invalid " + fieldName);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化严格的非零 EVM 合约地址。
     */
    private String normalizeAddress(String value, String chainName) {
        if (value == null || !ADDRESS_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    chainName + " deployment receipt contract address is invalid");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (ZERO_ADDRESS.equals(normalized)) {
            throw new IllegalStateException(
                    chainName + " deployment receipt contract address must not be zero");
        }
        return normalized;
    }
}
