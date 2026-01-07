package cn.flying.test.mocks;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreFileResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MockResultHelper {

    public static <T> Result<T> success(T data) {
        return Result.success(data);
    }

    public static <T> Result<T> success() {
        return Result.success(null);
    }

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> failure(String message) {
        return failure(500, message);
    }

    public static <T> Result<T> notFound(String message) {
        return failure(404, message);
    }

    public static <T> Result<T> unauthorized(String message) {
        return failure(401, message);
    }

    public static <T> Result<T> serviceUnavailable(String message) {
        return failure(503, message);
    }

    public static <T> Result<T> contractError(String message) {
        return failure(10001, message);
    }

    public static <T> Result<T> storageError(String message) {
        return failure(10002, message);
    }

    public static <T> Result<T> quorumNotReached(String message) {
        return failure(10003, message);
    }

    public static Result<StoreFileResponse> blockchainStoreSuccess(String fileHash) {
        return success(StoreFileResponse.builder()
                .transactionHash("0x" + UUID.randomUUID().toString().replace("-", ""))
                .fileHash(fileHash)
                .build());
    }

    public static Result<StoreFileResponse> blockchainStoreSuccess() {
        return blockchainStoreSuccess("sha256_" + System.nanoTime());
    }

    public static Result<StoreFileResponse> blockchainStoreFailure() {
        return contractError("Contract execution failed");
    }

    public static Result<StoreFileResponse> blockchainTimeout() {
        return failure(504, "Blockchain operation timeout");
    }

    public static Result<String> storageUploadSuccess(String fileHash) {
        return success("chunks/" + fileHash);
    }

    public static Result<String> storageUploadSuccess() {
        return storageUploadSuccess("sha256_" + System.nanoTime());
    }

    public static Result<String> storageUploadFailure() {
        return storageError("Failed to upload file to storage");
    }

    public static Result<String> storageQuorumFailure() {
        return quorumNotReached("Quorum write failed: only 1/3 replicas succeeded");
    }

    public static Result<List<byte[]>> storageDownloadSuccess(byte[]... contents) {
        return success(List.of(contents));
    }

    public static Result<List<byte[]>> storageDownloadFailure() {
        return storageError("Failed to download file from storage");
    }

    public static Result<List<String>> storageUrlListSuccess(String... urls) {
        return success(List.of(urls));
    }

    public static Result<Boolean> deleteSuccess() {
        return success(true);
    }

    public static Result<Boolean> deleteFailure() {
        return failure("Delete operation failed");
    }

    public static Result<String> shareSuccess(String shareCode) {
        return success(shareCode);
    }

    public static Result<String> shareSuccess() {
        return shareSuccess(UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    }

    public static Result<Map<String, Boolean>> clusterHealthAllHealthy(String... nodeNames) {
        Map<String, Boolean> health = new HashMap<>();
        for (String node : nodeNames) {
            health.put(node, true);
        }
        return success(health);
    }

    public static Result<Map<String, Boolean>> clusterHealthPartialFailure(List<String> healthyNodes, List<String> unhealthyNodes) {
        Map<String, Boolean> health = new HashMap<>();
        for (String node : healthyNodes) {
            health.put(node, true);
        }
        for (String node : unhealthyNodes) {
            health.put(node, false);
        }
        return success(health);
    }

    public static Result<List<String>> chunkLocationsSuccess(String... nodeNames) {
        return success(List.of(nodeNames));
    }

    public static String generateTransactionHash() {
        return "0x" + UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateFileHash() {
        return "sha256_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    public static String generateShareCode() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }
}
