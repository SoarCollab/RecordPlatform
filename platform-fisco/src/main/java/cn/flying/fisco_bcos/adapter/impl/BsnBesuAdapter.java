package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.*;
import cn.flying.fisco_bcos.config.BsnBesuConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Int256;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.tx.gas.StaticGasProvider;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * BSN Hyperledger Besu 节点适配器
 * 用于生产环境，连接 BSN 区块链服务网络托管的 Hyperledger Besu 节点
 *
 * <p>激活条件: {@code blockchain.active=bsn-besu}
 *
 * <p>使用 Web3j SDK 与 EVM 兼容链交互，调用与 FISCO BCOS 相同的 Solidity 合约。
 *
 * <p>注意：本类使用 {@link org.web3j.abi.datatypes.Function} 进行 ABI 编码，
 * 这是 Web3j 的标准合约调用方式，与 JavaScript 的动态代码执行无关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
public class BsnBesuAdapter implements BlockChainAdapter {

    private final Web3j web3j;
    private final Credentials credentials;
    private final StaticGasProvider gasProvider;
    private final BsnBesuConfig besuConfig;

    private String sharingContractAddress;

    /**
     * 分享文件结构体（与 Solidity 中 Storage.File 对齐）
     */
    public static class SharingFileStruct extends DynamicStruct {
        public Utf8String fileName;
        public Utf8String uploader;
        public Utf8String content;
        public Utf8String param;
        public Bytes32 fileHash;
        public Uint256 uploadTime;

        /**
         * 构造分享文件结构体（用于 ABI 解码）
         */
        public SharingFileStruct(
                Utf8String fileName,
                Utf8String uploader,
                Utf8String content,
                Utf8String param,
                Bytes32 fileHash,
                Uint256 uploadTime
        ) {
            super(fileName, uploader, content, param, fileHash, uploadTime);
            this.fileName = fileName;
            this.uploader = uploader;
            this.content = content;
            this.param = param;
            this.fileHash = fileHash;
            this.uploadTime = uploadTime;
        }

        /**
         * 构造分享文件结构体（用于 ABI 编码）
         */
        public SharingFileStruct(
                String fileName,
                String uploader,
                String content,
                String param,
                byte[] fileHash,
                BigInteger uploadTime
        ) {
            super(
                    new Utf8String(fileName),
                    new Utf8String(uploader),
                    new Utf8String(content),
                    new Utf8String(param),
                    new Bytes32(fileHash),
                    new Uint256(uploadTime)
            );
            this.fileName = new Utf8String(fileName);
            this.uploader = new Utf8String(uploader);
            this.content = new Utf8String(content);
            this.param = new Utf8String(param);
            this.fileHash = new Bytes32(fileHash);
            this.uploadTime = new Uint256(uploadTime);
        }
    }

    @PostConstruct
    public void init() {
        this.sharingContractAddress = besuConfig.getContracts().getSharing();
        if (sharingContractAddress == null || sharingContractAddress.isEmpty()) {
            throw new IllegalStateException(
                "[BSN Besu] Sharing 合约地址未配置，请设置 blockchain.bsn-besu.contracts.sharing");
        }
        log.info("[BSN Besu] Sharing 合约地址: {}", sharingContractAddress);

        // 验证 Storage 合约地址（如果后续需要）
        String storageAddress = besuConfig.getContracts().getStorage();
        if (storageAddress == null || storageAddress.isEmpty()) {
            log.warn("[BSN Besu] Storage 合约地址未配置，部分功能可能不可用");
        }
    }

    @Override
    public ChainType getChainType() {
        return ChainType.BSN_BESU;
    }

    // ==================== 文件存储操作 ====================

    @Override
    public ChainReceipt storeFile(String uploader, String fileName, String content, String param) {
        try {
            // Web3j ABI Function 用于编码合约调用，非动态代码执行
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "storeFile",
                    Arrays.asList(
                            new Utf8String(fileName),
                            new Utf8String(uploader),
                            new Utf8String(content),
                            new Utf8String(param)
                    ),
                    Collections.singletonList(new TypeReference<Bytes32>() {
                    })
            );

            EthSendTransaction txResponse = sendTransaction(abiFunction);

            if (txResponse.hasError()) {
                throw new ChainException(ChainType.BSN_BESU, "storeFile", txResponse.getError().getMessage());
            }

            String txHash = txResponse.getTransactionHash();
            TransactionReceipt receipt = waitForReceipt(txHash);
            String fileHash = extractFileHashFromLogs(receipt);

            return ChainReceipt.builder()
                    .transactionHash(normalizeHash(txHash))
                    .fileHash(fileHash)
                    .blockNumber(receipt.getBlockNumber().longValue())
                    .gasUsed(receipt.getGasUsed().longValue())
                    .success(receipt.isStatusOK())
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu storeFile] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "storeFile", e.getMessage(), e);
        }
    }

    @Override
    public List<ChainFileInfo> getUserFiles(String uploader) {
        try {
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "getUserFiles",
                    Collections.singletonList(new Utf8String(uploader)),
                    Arrays.asList(
                            new TypeReference<DynamicArray<Utf8String>>() {
                            },
                            new TypeReference<DynamicArray<Bytes32>>() {
                            }
                    )
            );

            List<Type> result = callContract(abiFunction);
            List<ChainFileInfo> fileList = new ArrayList<>();

            if (result.size() >= 2) {
                @SuppressWarnings("unchecked")
                List<Utf8String> fileNames = ((DynamicArray<Utf8String>) result.get(0)).getValue();
                @SuppressWarnings("unchecked")
                List<Bytes32> fileHashes = ((DynamicArray<Bytes32>) result.get(1)).getValue();

                for (int i = 0; i < Math.min(fileNames.size(), fileHashes.size()); i++) {
                    String hash = Numeric.toHexStringNoPrefix(fileHashes.get(i).getValue());
                    if (!hash.isEmpty() && !hash.equals("0".repeat(64))) {
                        fileList.add(ChainFileInfo.builder()
                                .fileName(fileNames.get(i).getValue())
                                .fileHash(hash)
                                .build());
                    }
                }
            }

            return fileList;
        } catch (Exception e) {
            log.error("[BSN Besu getUserFiles] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getUserFiles", e.getMessage(), e);
        }
    }

    @Override
    public ChainFileDetail getFile(String uploader, String fileHash) {
        try {
            byte[] hashBytes = Numeric.hexStringToByteArray(fileHash);

            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "getFile",
                    Arrays.asList(new Utf8String(uploader), new Bytes32(hashBytes)),
                    Arrays.asList(
                            new TypeReference<Utf8String>() {
                            },
                            new TypeReference<Utf8String>() {
                            },
                            new TypeReference<Utf8String>() {
                            },
                            new TypeReference<Utf8String>() {
                            },
                            new TypeReference<Bytes32>() {
                            },
                            new TypeReference<Uint256>() {
                            }
                    )
            );

            List<Type> result = callContract(abiFunction);

            if (result.size() < 6) {
                throw new ChainException(ChainType.BSN_BESU, "getFile", "File not found");
            }

            BigInteger uploadTimeNanos = ((Uint256) result.get(5)).getValue();

            // Contract File struct order: fileName(0), uploader(1), content(2), param(3), fileHash(4), uploadTime(5)
            return ChainFileDetail.builder()
                    .fileName(((Utf8String) result.get(0)).getValue())
                    .uploader(((Utf8String) result.get(1)).getValue())
                    .content(((Utf8String) result.get(2)).getValue())
                    .param(((Utf8String) result.get(3)).getValue())
                    .fileHash(fileHash)
                    .uploadTimestamp(uploadTimeNanos.divide(BigInteger.valueOf(1_000_000)).longValue())
                    .uploadTimeFormatted(formatTimestamp(uploadTimeNanos.longValue()))
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu getFile] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getFile", e.getMessage(), e);
        }
    }

    @Override
    public ChainReceipt deleteFiles(String uploader, List<String> fileHashes) {
        try {
            List<Bytes32> hashArray = fileHashes.stream()
                    .map(h -> new Bytes32(Numeric.hexStringToByteArray(h)))
                    .toList();

            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "deleteFiles",
                    Arrays.asList(new Utf8String(uploader), new DynamicArray<>(Bytes32.class, hashArray)),
                    Collections.emptyList()
            );

            EthSendTransaction txResponse = sendTransaction(abiFunction);

            if (txResponse.hasError()) {
                throw new ChainException(ChainType.BSN_BESU, "deleteFiles", txResponse.getError().getMessage());
            }

            TransactionReceipt receipt = waitForReceipt(txResponse.getTransactionHash());

            return ChainReceipt.builder()
                    .transactionHash(normalizeHash(txResponse.getTransactionHash()))
                    .success(receipt.isStatusOK())
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu deleteFiles] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "deleteFiles", e.getMessage(), e);
        }
    }

    // ==================== 文件分享操作 ====================

    @Override
    public ChainReceipt shareFiles(String uploader, List<String> fileHashes, int expireMinutes) {
        try {
            List<Bytes32> hashArray = fileHashes.stream()
                    .map(h -> new Bytes32(Numeric.hexStringToByteArray(h)))
                    .toList();

            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "shareFiles",
                    Arrays.asList(
                            new Utf8String(uploader),
                            new DynamicArray<>(Bytes32.class, hashArray),
                            new Uint256(expireMinutes)
                    ),
                    Collections.singletonList(new TypeReference<Utf8String>() {
                    })
            );

            EthSendTransaction txResponse = sendTransaction(abiFunction);

            if (txResponse.hasError()) {
                throw new ChainException(ChainType.BSN_BESU, "shareFiles", txResponse.getError().getMessage());
            }

            TransactionReceipt receipt = waitForReceipt(txResponse.getTransactionHash());
            String shareCode = extractShareCodeFromLogs(receipt);

            return ChainReceipt.builder()
                    .transactionHash(normalizeHash(txResponse.getTransactionHash()))
                    .shareCode(shareCode)
                    .success(receipt.isStatusOK())
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu shareFiles] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "shareFiles", e.getMessage(), e);
        }
    }

    /**
     * 获取分享文件信息并解析过期时间（取消分享时 expireTime=-1）
     */
    @Override
    public ChainShareInfo getSharedFiles(String shareCode) {
        try {
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "getSharedFiles",
                    Collections.singletonList(new Utf8String(shareCode)),
                    Arrays.asList(
                            new TypeReference<Utf8String>() {
                            },
                            new TypeReference<DynamicArray<SharingFileStruct>>() {
                            },
                            new TypeReference<Int256>() {
                            }
                    )
            );

            List<Type> result = callContract(abiFunction);

            if (result.size() < 3) {
                throw new ChainException(ChainType.BSN_BESU, "getSharedFiles", "Share not found");
            }

            String uploaderResult = ((Utf8String) result.get(0)).getValue();
            @SuppressWarnings("unchecked")
            List<SharingFileStruct> files = ((DynamicArray<SharingFileStruct>) result.get(1)).getValue();
            BigInteger expireTime = ((Int256) result.get(2)).getValue();

            List<String> fileHashList = files.stream()
                    .map(file -> Numeric.toHexStringNoPrefix(file.fileHash.getValue()))
                    .filter(h -> !h.equals("0".repeat(64)))
                    .toList();

            return ChainShareInfo.builder()
                    .uploader(uploaderResult)
                    .fileHashList(fileHashList)
                    .shareCode(shareCode)
                    .expireTimestamp(expireTime.longValue())
                    .isValid(expireTime.signum() >= 0)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu getSharedFiles] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getSharedFiles", e.getMessage(), e);
        }
    }

    @Override
    public ChainReceipt cancelShare(String shareCode) {
        try {
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "cancelShare",
                    Collections.singletonList(new Utf8String(shareCode)),
                    Collections.emptyList()
            );

            EthSendTransaction txResponse = sendTransaction(abiFunction);

            if (txResponse.hasError()) {
                throw new ChainException(ChainType.BSN_BESU, "cancelShare", txResponse.getError().getMessage());
            }

            TransactionReceipt receipt = waitForReceipt(txResponse.getTransactionHash());

            return ChainReceipt.builder()
                    .transactionHash(normalizeHash(txResponse.getTransactionHash()))
                    .success(receipt.isStatusOK())
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu cancelShare] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "cancelShare", e.getMessage(), e);
        }
    }

    @Override
    public List<String> getUserShareCodes(String uploader) {
        try {
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "getUserShareCodes",
                    Collections.singletonList(new Utf8String(uploader)),
                    Collections.singletonList(new TypeReference<DynamicArray<Utf8String>>() {})
            );

            List<Type> result = callContract(abiFunction);

            if (result.isEmpty()) {
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            List<Utf8String> shareCodes = ((DynamicArray<Utf8String>) result.get(0)).getValue();

            return shareCodes.stream()
                    .map(Utf8String::getValue)
                    .filter(code -> code != null && !code.isEmpty())
                    .toList();

        } catch (Exception e) {
            log.error("[BSN Besu getUserShareCodes] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getUserShareCodes", e.getMessage(), e);
        }
    }

    @Override
    public ChainShareInfo getShareInfo(String shareCode) {
        try {
            org.web3j.abi.datatypes.Function abiFunction = new org.web3j.abi.datatypes.Function(
                    "getShareInfo",
                    Collections.singletonList(new Utf8String(shareCode)),
                    Arrays.asList(
                            new TypeReference<Utf8String>() {},      // uploader
                            new TypeReference<DynamicArray<Bytes32>>() {}, // fileHashes
                            new TypeReference<Uint256>() {},         // expireTime
                            new TypeReference<org.web3j.abi.datatypes.Bool>() {} // isValid
                    )
            );

            List<Type> result = callContract(abiFunction);

            if (result.size() < 4) {
                throw new ChainException(ChainType.BSN_BESU, "getShareInfo", "Share not found");
            }

            String uploaderResult = ((Utf8String) result.get(0)).getValue();
            @SuppressWarnings("unchecked")
            List<Bytes32> hashes = ((DynamicArray<Bytes32>) result.get(1)).getValue();
            BigInteger expireTime = ((Uint256) result.get(2)).getValue();
            boolean isValid = ((org.web3j.abi.datatypes.Bool) result.get(3)).getValue();

            List<String> fileHashList = hashes.stream()
                    .map(h -> Numeric.toHexStringNoPrefix(h.getValue()))
                    .filter(h -> !h.equals("0".repeat(64)))
                    .toList();

            return ChainShareInfo.builder()
                    .uploader(uploaderResult)
                    .fileHashList(fileHashList)
                    .shareCode(shareCode)
                    .expireTimestamp(expireTime.longValue())
                    .isValid(isValid)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu getShareInfo] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getShareInfo", e.getMessage(), e);
        }
    }

    // ==================== 链状态查询 ====================

    @Override
    public ChainStatus getChainStatus() {
        try {
            EthBlockNumber blockNumber = web3j.ethBlockNumber().send();

            return ChainStatus.builder()
                    .chainType(ChainType.BSN_BESU)
                    .blockNumber(blockNumber.getBlockNumber().longValue())
                    .healthy(true)
                    .lastUpdateTime(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            log.error("[BSN Besu getChainStatus] 异常", e);
            return ChainStatus.builder()
                    .chainType(ChainType.BSN_BESU)
                    .healthy(false)
                    .lastUpdateTime(System.currentTimeMillis())
                    .build();
        }
    }

    @Override
    public ChainTransaction getTransaction(String txHash) {
        try {
            EthTransaction ethTx = web3j.ethGetTransactionByHash(txHash).send();

            if (ethTx.getTransaction().isEmpty()) {
                throw new ChainException(ChainType.BSN_BESU, "getTransaction", "Transaction not found");
            }

            org.web3j.protocol.core.methods.response.Transaction tx = ethTx.getTransaction().get();
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();

            return ChainTransaction.builder()
                    .hash(tx.getHash())
                    .chainId(besuConfig.getChainId() != null ? besuConfig.getChainId().toString() : null)
                    .from(tx.getFrom())
                    .to(tx.getTo())
                    .input(tx.getInput())
                    .blockNumber(tx.getBlockNumber().longValue())
                    .gasUsed(receiptResponse.getTransactionReceipt()
                            .map(r -> r.getGasUsed().longValue())
                            .orElse(0L))
                    .success(receiptResponse.getTransactionReceipt()
                            .map(TransactionReceipt::isStatusOK)
                            .orElse(false))
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("[BSN Besu getTransaction] 异常", e);
            throw new ChainException(ChainType.BSN_BESU, "getTransaction", e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            ChainStatus status = getChainStatus();
            return status.isHealthy() && status.getBlockNumber() != null && status.getBlockNumber() > 0;
        } catch (Exception e) {
            log.warn("[BSN Besu isHealthy] 健康检查失败", e);
            return false;
        }
    }

    // ==================== 私有辅助方法 ====================

    private String extractShareCodeFromLogs(TransactionReceipt receipt) {
        if (!receipt.getLogs().isEmpty()) {
            Log logEntry = receipt.getLogs().get(0);
            if (logEntry.getData() != null && logEntry.getData().length() > 2) {
                try {
                    return new String(Numeric.hexStringToByteArray(logEntry.getData())).trim();
                } catch (Exception e) {
                    return logEntry.getData();
                }
            }
        }
        return "";
    }

    private String formatTimestamp(long nanos) {
        long millis = nanos / 1_000_000;
        java.time.Instant instant = java.time.Instant.ofEpochMilli(millis);
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant);
    }

    private List<Type> callContract(org.web3j.abi.datatypes.Function abiFunction) throws Exception {
        String encodedFunction = FunctionEncoder.encode(abiFunction);

        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(
                        credentials.getAddress(),
                        sharingContractAddress,
                        encodedFunction
                ),
                DefaultBlockParameterName.LATEST
        ).send();

        if (response.hasError()) {
            throw new ChainException(ChainType.BSN_BESU, "callContract", response.getError().getMessage());
        }

        return FunctionReturnDecoder.decode(response.getValue(), abiFunction.getOutputParameters());
    }

    private EthSendTransaction sendTransaction(org.web3j.abi.datatypes.Function abiFunction) throws Exception {
        String encodedFunction = FunctionEncoder.encode(abiFunction);

        BigInteger nonce = web3j.ethGetTransactionCount(
                credentials.getAddress(), DefaultBlockParameterName.LATEST
        ).send().getTransactionCount();

        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasProvider.getGasPrice(),
                gasProvider.getGasLimit(),
                sharingContractAddress,
                encodedFunction
        );

        byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
        String hexValue = Numeric.toHexString(signedMessage);

        return web3j.ethSendRawTransaction(hexValue).send();
    }

    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        int attempts = 0;
        int maxAttempts = 40;

        while (attempts < maxAttempts) {
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();

            if (receiptResponse.getTransactionReceipt().isPresent()) {
                return receiptResponse.getTransactionReceipt().get();
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // 恢复中断状态并终止等待
                Thread.currentThread().interrupt();
                throw new ChainException(ChainType.BSN_BESU, "waitForReceipt", "等待交易确认被中断");
            }
            attempts++;
        }

        throw new ChainException(ChainType.BSN_BESU, "waitForReceipt", "Transaction not confirmed in time");
    }

    private String extractFileHashFromLogs(TransactionReceipt receipt) {
        if (!receipt.getLogs().isEmpty()) {
            Log logEntry = receipt.getLogs().get(0);
            if (logEntry.getTopics().size() > 1) {
                return normalizeHash(logEntry.getTopics().get(1));
            }
        }
        return "";
    }

    private String normalizeHash(String hash) {
        if (hash == null) return "";
        return hash.startsWith("0x") || hash.startsWith("0X") ? hash.substring(2) : hash;
    }
}
