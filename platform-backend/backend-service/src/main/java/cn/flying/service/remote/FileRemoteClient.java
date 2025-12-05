package cn.flying.service.remote;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class FileRemoteClient {

    @DubboReference
    private BlockChainService blockChainService;

    @DubboReference
    private DistributedStorageService storageService;

    @Deprecated
    @CircuitBreaker(name = "storageService", fallbackMethod = "storeFileFallback")
    @Retry(name = "storageService")
    public Result<Map<String, String>> storeFile(List<byte[]> fileByteList, List<String> fileHashList) {
        return storageService.storeFile(fileByteList, fileHashList);
    }

    private Result<Map<String, String>> storeFileFallback(List<byte[]> fileByteList, List<String> fileHashList, Throwable t) {
        log.error("Storage service storeFile failed", t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, null);
    }

    @CircuitBreaker(name = "storageService", fallbackMethod = "storeFileChunkFallback")
    @Retry(name = "storageService")
    public Result<String> storeFileChunk(byte[] fileData, String fileHash) {
        return storageService.storeFileChunk(fileData, fileHash);
    }

    private Result<String> storeFileChunkFallback(byte[] fileData, String fileHash, Throwable t) {
        log.error("Storage service storeFileChunk failed, hash={}", fileHash, t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "storeFileOnChainFallback")
    @Retry(name = "blockChainService")
    public Result<List<String>> storeFileOnChain(String userId, String fileName, String fileParam, String fileContent) {
        return blockChainService.storeFile(userId, fileName, fileParam, fileContent);
    }

    private Result<List<String>> storeFileOnChainFallback(String userId, String fileName, String fileParam, String fileContent, Throwable t) {
        log.error("BlockChain service storeFile failed, userId={}", userId, t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getFileFallback")
    @Retry(name = "blockChainService")
    public Result<FileDetailVO> getFile(String userId, String fileHash) {
        return blockChainService.getFile(userId, fileHash);
    }

    private Result<FileDetailVO> getFileFallback(String userId, String fileHash, Throwable t) {
        log.error("BlockChain service getFile failed, userId={}, fileHash={}", userId, fileHash, t);
        return new Result<>(ResultEnum.GET_USER_FILE_ERROR, null);
    }

    @CircuitBreaker(name = "storageService", fallbackMethod = "getFileUrlListFallback")
    @Retry(name = "storageService")
    public Result<List<String>> getFileUrlListByHash(List<String> urls, List<String> keys) {
        return storageService.getFileUrlListByHash(urls, keys);
    }

    private Result<List<String>> getFileUrlListFallback(List<String> urls, List<String> keys, Throwable t) {
        log.error("Storage service getFileUrlListByHash failed", t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, List.of());
    }

    @CircuitBreaker(name = "storageService", fallbackMethod = "getFileListFallback")
    @Retry(name = "storageService")
    public Result<List<byte[]>> getFileListByHash(List<String> urls, List<String> keys) {
        return storageService.getFileListByHash(urls, keys);
    }

    private Result<List<byte[]>> getFileListFallback(List<String> urls, List<String> keys, Throwable t) {
        log.error("Storage service getFileListByHash failed", t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, List.of());
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getTransactionFallback")
    @Retry(name = "blockChainService")
    public Result<TransactionVO> getTransactionByHash(String transactionHash) {
        return blockChainService.getTransactionByHash(transactionHash);
    }

    private Result<TransactionVO> getTransactionFallback(String transactionHash, Throwable t) {
        log.error("BlockChain service getTransaction failed, hash={}", transactionHash, t);
        return new Result<>(ResultEnum.TRANSACTION_NOT_FOUND, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "shareFilesFallback")
    @Retry(name = "blockChainService")
    public Result<String> shareFiles(String userId, List<String> fileHash, Integer maxAccesses) {
        return blockChainService.shareFiles(userId, fileHash, maxAccesses);
    }

    private Result<String> shareFilesFallback(String userId, List<String> fileHash, Integer maxAccesses, Throwable t) {
        log.error("BlockChain service shareFiles failed, userId={}", userId, t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getSharedFilesFallback")
    @Retry(name = "blockChainService")
    public Result<SharingVO> getSharedFiles(String sharingCode) {
        return blockChainService.getSharedFiles(sharingCode);
    }

    private Result<SharingVO> getSharedFilesFallback(String sharingCode, Throwable t) {
        log.error("BlockChain service getSharedFiles failed, code={}", sharingCode, t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }
}
