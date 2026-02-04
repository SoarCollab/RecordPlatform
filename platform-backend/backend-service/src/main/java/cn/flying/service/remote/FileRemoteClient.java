package cn.flying.service.remote;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
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

    @DubboReference(id = "blockChainServiceFileRemoteClient", version = BlockChainService.VERSION, providedBy = "RecordPlatform_fisco")
    private BlockChainService blockChainService;

    @DubboReference(id = "storageServiceFileRemoteClient", version = DistributedStorageService.VERSION, providedBy = "RecordPlatform_storage")
    private DistributedStorageService storageService;

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
    public Result<StoreFileResponse> storeFileOnChain(StoreFileRequest request) {
        return blockChainService.storeFile(request);
    }

    private Result<StoreFileResponse> storeFileOnChainFallback(StoreFileRequest request, Throwable t) {
        log.error("BlockChain service storeFile failed, uploader={}", request.uploader(), t);
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
    public Result<String> shareFiles(ShareFilesRequest request) {
        return blockChainService.shareFiles(request);
    }

    private Result<String> shareFilesFallback(ShareFilesRequest request, Throwable t) {
        log.error("BlockChain service shareFiles failed, uploader={}", request.uploader(), t);
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

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "deleteFilesFallback")
    @Retry(name = "blockChainService")
    public Result<Boolean> deleteFiles(DeleteFilesRequest request) {
        return blockChainService.deleteFiles(request);
    }

    private Result<Boolean> deleteFilesFallback(DeleteFilesRequest request, Throwable t) {
        log.error("BlockChain service deleteFiles failed, uploader={}", request.uploader(), t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, false);
    }

    @CircuitBreaker(name = "storageService", fallbackMethod = "deleteStorageFileFallback")
    @Retry(name = "storageService")
    public Result<Boolean> deleteStorageFile(Map<String, String> fileContent) {
        return storageService.deleteFile(fileContent);
    }

    private Result<Boolean> deleteStorageFileFallback(Map<String, String> fileContent, Throwable t) {
        log.error("Storage service deleteFile failed", t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, false);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "cancelShareFallback")
    @Retry(name = "blockChainService")
    public Result<Boolean> cancelShare(CancelShareRequest request) {
        return blockChainService.cancelShare(request);
    }

    private Result<Boolean> cancelShareFallback(CancelShareRequest request, Throwable t) {
        log.error("BlockChain service cancelShare failed, shareCode={}", request.shareCode(), t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, false);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getUserShareCodesFallback")
    @Retry(name = "blockChainService")
    public Result<List<String>> getUserShareCodes(String uploader) {
        return blockChainService.getUserShareCodes(uploader);
    }

    private Result<List<String>> getUserShareCodesFallback(String uploader, Throwable t) {
        log.error("BlockChain service getUserShareCodes failed, uploader={}", uploader, t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, List.of());
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getShareInfoFallback")
    @Retry(name = "blockChainService")
    public Result<SharingVO> getShareInfo(String shareCode) {
        return blockChainService.getShareInfo(shareCode);
    }

    private Result<SharingVO> getShareInfoFallback(String shareCode, Throwable t) {
        log.error("BlockChain service getShareInfo failed, shareCode={}", shareCode, t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }
}
