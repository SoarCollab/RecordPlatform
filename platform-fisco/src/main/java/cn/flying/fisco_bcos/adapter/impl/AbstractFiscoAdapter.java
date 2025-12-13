package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.*;
import cn.flying.fisco_bcos.model.bo.*;
import cn.flying.fisco_bcos.service.SharingService;
import cn.flying.fisco_bcos.utils.Convert;
import lombok.extern.slf4j.Slf4j;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.flying.fisco_bcos.parser.ContractResponseParser.*;

/**
 * FISCO BCOS 适配器抽象基类
 * 封装与 FISCO BCOS SDK 交互的公共逻辑，子类只需实现 {@link #getChainType()} 和 {@link #getSharingService()}
 *
 * <p>实现类:
 * <ul>
 *   <li>{@link LocalFiscoAdapter} - 本地 FISCO BCOS 节点</li>
 *   <li>{@link BsnFiscoAdapter} - BSN 托管的 FISCO BCOS 节点</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractFiscoAdapter implements BlockChainAdapter {

    /**
     * 获取 SharingService 实例
     * 由子类通过依赖注入提供
     */
    protected abstract SharingService getSharingService();

    /**
     * 获取日志前缀，用于区分不同链的日志
     */
    protected String getLogPrefix() {
        return "[" + getChainType().getDisplayName() + "]";
    }

    // ==================== 文件存储操作 ====================

    @Override
    public ChainReceipt storeFile(String uploader, String fileName, String content, String param) {
        try {
            TransactionResponse response = getSharingService().storeFile(
                    new SharingStoreFileInputBO(fileName, uploader, content, param));

            if (response == null) {
                throw new ChainException(getChainType(), "storeFile", "Response is null");
            }

            TransactionReceipt receipt = response.getTransactionReceipt();
            if (receipt == null || receipt.getTransactionHash() == null) {
                throw new ChainException(getChainType(), "storeFile", "Transaction receipt is null");
            }

            String transactionHash = normalizeHash(receipt.getTransactionHash());

            if (response.getReturnCode() != 0) {
                log.warn("{} [storeFile] 合约返回错误: {}", getLogPrefix(), response.getReturnMessage());
                throw new ChainException(getChainType(), "storeFile", response.getReturnMessage());
            }

            if (!(response.getReturnObject() instanceof List<?> returnList) || returnList.isEmpty()) {
                throw new ChainException(getChainType(), "storeFile", "Invalid return value");
            }

            Optional<String> fileHashOpt = safeGet(returnList, 0, byte[].class)
                    .map(Convert::bytesToHex)
                    .map(this::normalizeHash);

            if (fileHashOpt.isEmpty()) {
                throw new ChainException(getChainType(), "storeFile", "Failed to extract fileHash");
            }

            return ChainReceipt.builder()
                    .transactionHash(transactionHash)
                    .fileHash(fileHashOpt.get())
                    .blockNumber(receipt.getBlockNumber().longValue())
                    .success(true)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [storeFile] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "storeFile", e.getMessage(), e);
        }
    }

    @Override
    public List<ChainFileInfo> getUserFiles(String uploader) {
        try {
            CallResponse response = getSharingService().getUserFiles(new SharingGetUserFilesInputBO(uploader));
            List<ChainFileInfo> fileList = new ArrayList<>();

            if (response == null || !(response.getReturnObject() instanceof List<?> files) || files.isEmpty()) {
                return fileList;
            }

            Object firstElement = files.getFirst();
            if (!(firstElement instanceof List<?> filesList)) {
                return fileList;
            }

            for (Object file : filesList) {
                if (file instanceof List<?> fileInfo && validateSize(fileInfo, 2)) {
                    String fileName = safeGetString(fileInfo, 0).orElse("");
                    Optional<String> hashOpt = extractFileHash(fileInfo, 1);

                    if (hashOpt.isPresent() && !hashOpt.get().isEmpty()) {
                        fileList.add(ChainFileInfo.builder()
                                .fileName(fileName)
                                .fileHash(hashOpt.get())
                                .build());
                    }
                }
            }

            return fileList;
        } catch (Exception e) {
            log.error("{} [getUserFiles] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getUserFiles", e.getMessage(), e);
        }
    }

    @Override
    public ChainFileDetail getFile(String uploader, String fileHash) {
        try {
            CallResponse response = getSharingService().getFile(
                    new SharingGetFileInputBO(uploader, Convert.hexTobyte(fileHash)));

            if (response == null || !(response.getReturnObject() instanceof List<?> returnList) || returnList.isEmpty()) {
                throw new ChainException(getChainType(), "getFile", "File not found");
            }

            Object returnValue = returnList.getFirst();
            if (!(returnValue instanceof List<?> fileInfo) || !validateSize(fileInfo, 6)) {
                throw new ChainException(getChainType(), "getFile", "Invalid file info structure");
            }

            long uploadTimeNanos = parseLong(fileInfo.get(5));
            long uploadTimeMillis = uploadTimeNanos / 1_000_000;
            String formattedUploadTime = Convert.timeStampToDate(uploadTimeMillis);

            return ChainFileDetail.builder()
                    .uploader(safeGetString(fileInfo, 0).orElse(""))
                    .fileName(safeGetString(fileInfo, 1).orElse(""))
                    .param(safeGetString(fileInfo, 2).orElse(""))
                    .content(safeGetString(fileInfo, 3).orElse(""))
                    .fileHash(fileHash)
                    .uploadTimestamp(uploadTimeNanos / 1_000_000)
                    .uploadTimeFormatted(formattedUploadTime)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [getFile] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getFile", e.getMessage(), e);
        }
    }

    @Override
    public ChainReceipt deleteFiles(String uploader, List<String> fileHashes) {
        try {
            List<byte[]> fileHashArr = fileHashes.stream()
                    .map(Convert::hexTobyte)
                    .toList();

            TransactionResponse response = getSharingService().deleteFiles(
                    new SharingDeleteFilesInputBO(uploader, fileHashArr));

            if (response != null && response.getReturnCode() == 0) {
                TransactionReceipt receipt = response.getTransactionReceipt();
                return ChainReceipt.builder()
                        .transactionHash(receipt != null ? normalizeHash(receipt.getTransactionHash()) : null)
                        .success(true)
                        .build();
            }

            String errorMsg = response != null ? response.getReturnMessage() : "null response";
            log.warn("{} [deleteFiles] 删除失败: {}", getLogPrefix(), errorMsg);
            throw new ChainException(getChainType(), "deleteFiles", errorMsg);

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [deleteFiles] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "deleteFiles", e.getMessage(), e);
        }
    }

    // ==================== 文件分享操作 ====================

    @Override
    public ChainReceipt shareFiles(String uploader, List<String> fileHashes, int expireMinutes) {
        try {
            List<byte[]> fileHashArr = fileHashes.stream()
                    .map(Convert::hexTobyte)
                    .toList();

            TransactionResponse response = getSharingService().shareFiles(
                    new SharingShareFilesInputBO(uploader, fileHashArr, expireMinutes));

            if (response == null) {
                throw new ChainException(getChainType(), "shareFiles", "Response is null");
            }

            if (response.getReturnCode() != 0) {
                throw new ChainException(getChainType(), "shareFiles", response.getReturnMessage());
            }

            if (!(response.getReturnObject() instanceof List<?> returnList)) {
                throw new ChainException(getChainType(), "shareFiles", "Invalid return value");
            }

            String shareCode = safeGetString(returnList, 0).orElse(null);
            TransactionReceipt receipt = response.getTransactionReceipt();

            return ChainReceipt.builder()
                    .transactionHash(receipt != null ? normalizeHash(receipt.getTransactionHash()) : null)
                    .shareCode(shareCode)
                    .success(true)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [shareFiles] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "shareFiles", e.getMessage(), e);
        }
    }

    @Override
    public ChainShareInfo getSharedFiles(String shareCode) {
        try {
            CallResponse response = getSharingService().getSharedFilesReadOnly(
                    new SharingGetSharedFilesInputBO(shareCode));

            if (response == null) {
                throw new ChainException(getChainType(), "getSharedFiles", "Share not found");
            }

            if (!(response.getReturnObject() instanceof List<?> returnList) || returnList.size() != 2) {
                throw new ChainException(getChainType(), "getSharedFiles", "Invalid return value");
            }

            String uploaderResult = safeGetString(returnList, 0).orElse("");
            List<String> fileList = new ArrayList<>();

            Optional<List<?>> filesOpt = safeGetList(returnList, 1);
            if (filesOpt.isPresent()) {
                for (Object file : filesOpt.get()) {
                    if (file instanceof List<?> fileInfo && validateSize(fileInfo, 6)) {
                        extractFileHash(fileInfo, 4).ifPresent(fileList::add);
                    }
                }
            }

            return ChainShareInfo.builder()
                    .uploader(uploaderResult)
                    .fileHashList(fileList)
                    .shareCode(shareCode)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [getSharedFiles] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getSharedFiles", e.getMessage(), e);
        }
    }

    @Override
    public ChainReceipt cancelShare(String shareCode) {
        try {
            TransactionResponse response = getSharingService().cancelShare(
                    new SharingCancelShareInputBO(shareCode));

            if (response == null) {
                throw new ChainException(getChainType(), "cancelShare", "Response is null");
            }

            if (response.getReturnCode() != 0) {
                throw new ChainException(getChainType(), "cancelShare", response.getReturnMessage());
            }

            TransactionReceipt receipt = response.getTransactionReceipt();

            return ChainReceipt.builder()
                    .transactionHash(receipt != null ? normalizeHash(receipt.getTransactionHash()) : null)
                    .success(true)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [cancelShare] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "cancelShare", e.getMessage(), e);
        }
    }

    @Override
    public List<String> getUserShareCodes(String uploader) {
        try {
            CallResponse response = getSharingService().getUserShareCodes(
                    new SharingGetUserShareCodesInputBO(uploader));

            if (response == null || !(response.getReturnObject() instanceof List<?> returnList)) {
                return new ArrayList<>();
            }

            // 返回值结构: [[shareCode1, shareCode2, ...]]
            if (returnList.isEmpty()) {
                return new ArrayList<>();
            }

            Object firstElement = returnList.getFirst();
            if (!(firstElement instanceof List<?> shareCodes)) {
                return new ArrayList<>();
            }

            List<String> result = new ArrayList<>();
            for (Object code : shareCodes) {
                if (code instanceof String shareCode && !shareCode.isEmpty()) {
                    result.add(shareCode);
                }
            }

            return result;

        } catch (Exception e) {
            log.error("{} [getUserShareCodes] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getUserShareCodes", e.getMessage(), e);
        }
    }

    @Override
    public ChainShareInfo getShareInfo(String shareCode) {
        try {
            CallResponse response = getSharingService().getShareInfo(
                    new SharingGetShareInfoInputBO(shareCode));

            if (response == null) {
                throw new ChainException(getChainType(), "getShareInfo", "Share not found");
            }

            if (!(response.getReturnObject() instanceof List<?> returnList) || returnList.size() < 4) {
                throw new ChainException(getChainType(), "getShareInfo", "Invalid return value");
            }

            // 返回值结构: [uploader, fileHashes[], expireTime, isValid]
            String uploaderResult = safeGetString(returnList, 0).orElse("");

            List<String> fileHashList = new ArrayList<>();
            Optional<List<?>> fileHashesOpt = safeGetList(returnList, 1);
            if (fileHashesOpt.isPresent()) {
                for (Object hash : fileHashesOpt.get()) {
                    if (hash instanceof byte[] hashBytes) {
                        fileHashList.add(normalizeHash(Convert.bytesToHex(hashBytes)));
                    }
                }
            }

            long expireTime = parseLong(returnList.get(2));
            boolean isValid = returnList.get(3) instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(returnList.get(3)));

            return ChainShareInfo.builder()
                    .uploader(uploaderResult)
                    .fileHashList(fileHashList)
                    .shareCode(shareCode)
                    .expireTimestamp(expireTime)
                    .isValid(isValid)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [getShareInfo] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getShareInfo", e.getMessage(), e);
        }
    }

    // ==================== 链状态查询 ====================

    @Override
    public ChainStatus getChainStatus() {
        try {
            TotalTransactionCount totalTransactionCount = getSharingService().getCurrentBlockChainMessage();
            ChainStatus.ChainStatusBuilder builder = ChainStatus.builder()
                    .chainType(getChainType())
                    .healthy(true)
                    .lastUpdateTime(System.currentTimeMillis());

            if (totalTransactionCount != null) {
                TotalTransactionCount.TransactionCountInfo info = totalTransactionCount.getTotalTransactionCount();
                if (info != null) {
                    builder.blockNumber(parseHexLong(info.getBlockNumber()))
                            .transactionCount(parseHexLong(info.getTransactionCount()))
                            .failedTransactionCount(parseHexLong(info.getFailedTransactionCount()));
                }
            }

            return builder.build();
        } catch (Exception e) {
            log.error("{} [getChainStatus] 异常", getLogPrefix(), e);
            return ChainStatus.builder()
                    .chainType(getChainType())
                    .healthy(false)
                    .lastUpdateTime(System.currentTimeMillis())
                    .build();
        }
    }

    @Override
    public ChainTransaction getTransaction(String txHash) {
        try {
            BcosTransaction transaction = getSharingService().getTransactionByHash(txHash);
            if (transaction == null || transaction.getResult() == null) {
                throw new ChainException(getChainType(), "getTransaction", "Transaction not found");
            }

            BcosTransactionReceipt receipt = getSharingService().getTransactionReceipt(txHash);

            var result = transaction.getResult();

            return ChainTransaction.builder()
                    .hash(result.getHash())
                    .chainId(result.getChainID())
                    .groupId(result.getGroupID())
                    .from(result.getFrom())
                    .to(result.getTo())
                    .input(result.getInput())
                    .signature(result.getSignature())
                    .importTime(result.getImportTime())
                    .success(receipt != null && receipt.getResult() != null)
                    .build();

        } catch (ChainException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} [getTransaction] 异常", getLogPrefix(), e);
            throw new ChainException(getChainType(), "getTransaction", e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            ChainStatus status = getChainStatus();
            return status.isHealthy() && status.getBlockNumber() != null && status.getBlockNumber() > 0;
        } catch (Exception e) {
            log.warn("{} [isHealthy] 健康检查失败", getLogPrefix(), e);
            return false;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析十六进制字符串为 long
     * 支持带或不带 0x/0X 前缀的格式
     *
     * @param hexValue 十六进制字符串
     * @return 解析后的 long 值，解析失败返回 0
     */
    protected long parseHexLong(String hexValue) {
        if (hexValue == null || hexValue.isEmpty()) return 0L;
        try {
            String cleanHex = hexValue;
            // 移除 0x/0X 前缀
            if (cleanHex.startsWith("0x") || cleanHex.startsWith("0X")) {
                cleanHex = cleanHex.substring(2);
            }
            if (cleanHex.isEmpty()) return 0L;
            return new java.math.BigInteger(cleanHex, 16).longValue();
        } catch (NumberFormatException e) {
            log.warn("[parseHexLong] 解析失败: {}", hexValue);
            return 0L;
        }
    }

    protected long parseLong(Object value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    protected Optional<String> extractFileHash(List<?> list, int index) {
        return safeGet(list, index, byte[].class)
                .map(Convert::bytesToHex)
                .map(this::normalizeHash);
    }

    protected String normalizeHash(String hash) {
        if (hash == null) return "";
        return hash.startsWith("0x") || hash.startsWith("0X") ? hash.substring(2) : hash;
    }
}
