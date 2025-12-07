package cn.flying.service.saga;

import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.util.List;

/**
 * Command object for file upload saga.
 */
@Data
@Builder
public class FileUploadCommand {
    private String requestId;
    private Long fileId;
    private Long userId;
    private String fileName;
    private String fileParam;
    private List<File> fileList;
    private List<String> fileHashList;
    private Long tenantId;
}
