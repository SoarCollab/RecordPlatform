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
    /**
     * 开始上传
     * @param fileName 文件名
     * @param fileSize 文件大小
     * @param contentType 文件类型
     * @param providedClientId 客户端提供的上传会话ID
     * @return
     */
    StartUploadVO startUpload(String fileName, long fileSize, String contentType, String providedClientId);
    /**
     * 上传分片
     * @param sessionId 上传会话ID
     * @param chunkNumber 分片序号
     * @param file 文件
     */
    void uploadChunk(String sessionId, int chunkNumber, MultipartFile file);
    /**
     * 获取上传进度
     * @param sessionId 上传会话ID
     * @return 上传进度
     */
    ProgressVO getUploadProgress(String sessionId);
    /**
     * 取消上传
     * @param sessionId 上传会话ID
     * @return 是否取消成功
     */
    boolean cancelUpload(String sessionId);
    /**
     * 完成上传
     * @param sessionId 上传会话ID
     */
    void completeUpload(String sessionId);
    /**
     * 暂停上传
     * @param sessionId 上传会话ID
     */
    void pauseUpload(String sessionId);
    /**
     * 恢复上传
     * @param sessionId 上传会话ID
     * @return 恢复上传结果
     */
    ResumeUploadVO resumeUpload(String sessionId);
    /**
     * 检查上传状态
     * @param sessionId 上传会话ID
     * @return 上传状态
     */
    FileUploadStatusVO checkFileStatus(String sessionId);
}
