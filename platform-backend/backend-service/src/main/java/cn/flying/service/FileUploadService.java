package cn.flying.service;

import cn.flying.dao.vo.file.FileUploadStatusVO;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * @program: RecordPlatform
 * @description: 文件分片上传服务
 * @author: flyingcoding
 * @create: 2025-04-01 13:19
 */

public interface FileUploadService {
    StartUploadVO startUpload(String fileName, long fileSize, String contentType, String providedClientId);
    void uploadChunk(String sessionId, int chunkNumber, MultipartFile file);
    ProgressVO getUploadProgress(String sessionId);
    boolean cancelUpload(String sessionId);
    void completeUpload(String sessionId);
    void pauseUpload(String sessionId);
    ResumeUploadVO resumeUpload(String sessionId);
    FileUploadStatusVO checkFileStatus(String sessionId);
}
