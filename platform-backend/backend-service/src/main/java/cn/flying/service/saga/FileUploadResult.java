package cn.flying.service.saga;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Result object for file upload saga.
 */
@Data
@AllArgsConstructor
public class FileUploadResult {
    private boolean success;
    private String transactionHash;
    private String fileHash;
    private String errorMessage;

    public static FileUploadResult success(String transactionHash, String fileHash) {
        return new FileUploadResult(true, transactionHash, fileHash, null);
    }

    public static FileUploadResult failure(String errorMessage) {
        return new FileUploadResult(false, null, null, errorMessage);
    }
}
