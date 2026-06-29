package cn.flying.service.remote;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.AbortDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.request.GetShareInfoRequest;
import cn.flying.platformapi.request.GetUserShareCodesRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchResponse;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.BlockChainMessage;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.platformapi.security.BlockChainRpcAuth;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Component
public class FileRemoteClient {

    @DubboReference(id = "blockChainServiceFileRemoteClient", version = BlockChainService.VERSION, providedBy = "RecordPlatform_fisco")
    private BlockChainService blockChainService;

    @DubboReference(id = "storageServiceFileRemoteClient", version = DistributedStorageService.VERSION, providedBy = "RecordPlatform_storage")
    private DistributedStorageService storageService;

    @Value("${record-platform.rpc.blockchain-token:}")
    private String blockchainRpcToken;

    /**
     * 启动时校验后端到 FISCO 的 RPC 共享令牌配置。
     */
    @PostConstruct
    void validateRpcTokenConfiguration() {
        if (!BlockChainRpcAuth.hasToken(blockchainRpcToken)) {
            throw new IllegalStateException(BlockChainRpcAuth.TOKEN_PROPERTY_NAME + " must be configured");
        }
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
    public Result<StoreFileResponse> storeFileOnChain(StoreFileRequest request) {
        return callBlockChain(() -> blockChainService.storeFile(request));
    }

    /**
     * Stores a Merkle batch root through the dedicated blockchain batch-attestation RPC.
     */
    @CircuitBreaker(name = "blockChainService", fallbackMethod = "storeAttestationBatchFallback")
    @Retry(name = "blockChainService")
    public Result<StoreAttestationBatchResponse> storeAttestationBatch(StoreAttestationBatchRequest request) {
        return callBlockChain(() -> blockChainService.storeAttestationBatch(request));
    }

    /**
     * Converts batch-attestation RPC failures into the shared blockchain error result.
     */
    private Result<StoreAttestationBatchResponse> storeAttestationBatchFallback(
            StoreAttestationBatchRequest request,
            Throwable t
    ) {
        log.error("BlockChain service storeAttestationBatch failed, batchNo={}",
                request != null ? request.batchNo() : null, t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, null);
    }

    private Result<StoreFileResponse> storeFileOnChainFallback(StoreFileRequest request, Throwable t) {
        log.error("BlockChain service storeFile failed, uploader={}", request.uploader(), t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getFileFallback")
    @Retry(name = "blockChainService")
    public Result<FileDetailVO> getFile(String userId, String fileHash) {
        return callBlockChain(() -> blockChainService.getFile(userId, fileHash));
    }

    private Result<FileDetailVO> getFileFallback(String userId, String fileHash, Throwable t) {
        log.error("BlockChain service getFile failed, userId={}, fileHash={}", userId, fileHash, t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, null);
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

    @CircuitBreaker(name = "storageService", fallbackMethod = "headObjectFallback")
    @Retry(name = "storageService")
    public Result<StorageObjectHeadVO> headObject(String filePath, String fileHash) {
        return storageService.headObject(filePath, fileHash);
    }

    private Result<StorageObjectHeadVO> headObjectFallback(String filePath, String fileHash, Throwable t) {
        log.error("Storage service headObject failed, path={}, hash={}", filePath, fileHash, t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, null);
    }

    /**
     * Creates object-storage presigned URLs for direct multipart upload chunks.
     */
    @CircuitBreaker(name = "storageService", fallbackMethod = "createDirectMultipartUploadFallback")
    @Retry(name = "storageService")
    public Result<CreateDirectMultipartUploadResponse> createDirectMultipartUpload(
            CreateDirectMultipartUploadRequest request) {
        return storageService.createDirectMultipartUpload(request);
    }

    private Result<CreateDirectMultipartUploadResponse> createDirectMultipartUploadFallback(
            CreateDirectMultipartUploadRequest request,
            Throwable t) {
        log.error("Storage service createDirectMultipartUpload failed, sessionId={}",
                request != null ? request.sessionId() : null, t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, null);
    }

    /**
     * Completes direct multipart upload by validating and promoting staging chunks.
     */
    @CircuitBreaker(name = "storageService", fallbackMethod = "completeDirectMultipartUploadFallback")
    @Retry(name = "storageService")
    public Result<CompleteDirectMultipartUploadResponse> completeDirectMultipartUpload(
            CompleteDirectMultipartUploadRequest request) {
        return storageService.completeDirectMultipartUpload(request);
    }

    private Result<CompleteDirectMultipartUploadResponse> completeDirectMultipartUploadFallback(
            CompleteDirectMultipartUploadRequest request,
            Throwable t) {
        log.error("Storage service completeDirectMultipartUpload failed, sessionId={}",
                request != null ? request.sessionId() : null, t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, null);
    }

    /**
     * Aborts direct multipart upload staging objects.
     */
    @CircuitBreaker(name = "storageService", fallbackMethod = "abortDirectMultipartUploadFallback")
    @Retry(name = "storageService")
    public Result<Boolean> abortDirectMultipartUpload(AbortDirectMultipartUploadRequest request) {
        return storageService.abortDirectMultipartUpload(request);
    }

    private Result<Boolean> abortDirectMultipartUploadFallback(AbortDirectMultipartUploadRequest request, Throwable t) {
        log.error("Storage service abortDirectMultipartUpload failed, sessionId={}",
                request != null ? request.sessionId() : null, t);
        return new Result<>(ResultEnum.FILE_SERVICE_ERROR, false);
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
        return callBlockChain(() -> blockChainService.getTransactionByHash(transactionHash));
    }

    private Result<TransactionVO> getTransactionFallback(String transactionHash, Throwable t) {
        log.error("BlockChain service getTransaction failed, hash={}", transactionHash, t);
        return new Result<>(ResultEnum.TRANSACTION_NOT_FOUND, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "shareFilesFallback")
    @Retry(name = "blockChainService")
    public Result<String> shareFiles(ShareFilesRequest request) {
        return callBlockChain(() -> blockChainService.shareFiles(request));
    }

    private Result<String> shareFilesFallback(ShareFilesRequest request, Throwable t) {
        log.error("BlockChain service shareFiles failed, uploader={}", request.uploader(), t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getSharedFilesFallback")
    @Retry(name = "blockChainService")
    public Result<SharingVO> getSharedFiles(String sharingCode) {
        return callBlockChain(() -> blockChainService.getSharedFiles(sharingCode));
    }

    private Result<SharingVO> getSharedFilesFallback(String sharingCode, Throwable t) {
        log.error("BlockChain service getSharedFiles failed, code={}", sharingCode, t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "deleteFilesFallback")
    @Retry(name = "blockChainService")
    public Result<Boolean> deleteFiles(DeleteFilesRequest request) {
        return callBlockChain(() -> blockChainService.deleteFiles(request));
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
        return callBlockChain(() -> blockChainService.cancelShare(request));
    }

    private Result<Boolean> cancelShareFallback(CancelShareRequest request, Throwable t) {
        log.error("BlockChain service cancelShare failed, shareCode={}", request.shareCode(), t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, false);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getUserShareCodesFallback")
    @Retry(name = "blockChainService")
    public Result<List<String>> getUserShareCodes(String uploader, String requester) {
        return callBlockChain(() -> blockChainService.getUserShareCodes(
                new GetUserShareCodesRequest(uploader, requester)));
    }

    private Result<List<String>> getUserShareCodesFallback(String uploader, String requester, Throwable t) {
        log.error("BlockChain service getUserShareCodes failed, uploader={}, requester={}", uploader, requester, t);
        return new Result<>(ResultEnum.BLOCKCHAIN_ERROR, List.of());
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getShareInfoFallback")
    @Retry(name = "blockChainService")
    public Result<SharingVO> getShareInfo(String shareCode, String requester) {
        return callBlockChain(() -> blockChainService.getShareInfo(
                new GetShareInfoRequest(shareCode, requester)));
    }

    private Result<SharingVO> getShareInfoFallback(String shareCode, String requester, Throwable t) {
        log.error("BlockChain service getShareInfo failed, shareCode={}, requester={}", shareCode, requester, t);
        return new Result<>(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
    }

    // ===== Health check methods (reuse existing Dubbo connections) =====

    @CircuitBreaker(name = "storageService", fallbackMethod = "getClusterHealthFallback")
    public Result<Map<String, Boolean>> getClusterHealth() {
        return storageService.getClusterHealth();
    }

    private Result<Map<String, Boolean>> getClusterHealthFallback(Throwable t) {
        log.error("Storage service getClusterHealth failed", t);
        return new Result<>(ResultEnum.SERVICE_CIRCUIT_OPEN, Map.of());
    }

    @CircuitBreaker(name = "storageService", fallbackMethod = "getStorageCapacityFallback")
    public Result<StorageCapacityVO> getStorageCapacity() {
        return storageService.getStorageCapacity();
    }

    private Result<StorageCapacityVO> getStorageCapacityFallback(Throwable t) {
        log.error("Storage service getStorageCapacity failed", t);
        return new Result<>(ResultEnum.SERVICE_CIRCUIT_OPEN, null);
    }

    @CircuitBreaker(name = "blockChainService", fallbackMethod = "getCurrentBlockChainMessageFallback")
    public Result<BlockChainMessage> getCurrentBlockChainMessage() {
        return callBlockChain(() -> blockChainService.getCurrentBlockChainMessage());
    }

    private Result<BlockChainMessage> getCurrentBlockChainMessageFallback(Throwable t) {
        log.error("BlockChain service getCurrentBlockChainMessage failed", t);
        return new Result<>(ResultEnum.SERVICE_CIRCUIT_OPEN, null);
    }

    /**
     * 为区块链 Dubbo 调用附加服务端共享令牌并在调用结束后恢复上下文。
     */
    private <T> T callBlockChain(Supplier<T> supplier) {
        if (!BlockChainRpcAuth.hasToken(blockchainRpcToken)) {
            throw new IllegalStateException("Blockchain RPC token is not configured");
        }

        RpcContext clientContext = RpcContext.getClientAttachment();
        String previousToken = clientContext.getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY);
        clientContext.setAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY, blockchainRpcToken);
        try {
            return supplier.get();
        } finally {
            restoreBlockChainRpcToken(clientContext, previousToken);
        }
    }

    /**
     * 恢复调用前的 Dubbo attachment，避免令牌泄露到其他 RPC 调用。
     */
    private void restoreBlockChainRpcToken(RpcContext clientContext, String previousToken) {
        if (previousToken == null) {
            clientContext.removeAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY);
        } else {
            clientContext.setAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY, previousToken);
        }
    }
}
