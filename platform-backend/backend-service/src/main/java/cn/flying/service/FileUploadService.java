package cn.flying.service;

import cn.flying.dao.vo.file.FileUploadStatusVO;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * @program: RecordPlatform
 * @description: 文件分片上传服务
 * @author: 王贝强
 * @create: 2025-04-01 13:19
 */

public interface FileUploadService {
    /**
     * 开始上传
     * @param uid 用户ID
     * @param fileName 文件名
     * @param fileSize 文件大小
     * @param contentType 文件类型
     * @param clientId 上传客户端ID
     * @param chunkSize 分片大小
     * @param totalChunks 分片总数
     * @return
     */
    StartUploadVO startUpload(String uid, String fileName, long fileSize, String contentType,String clientId, int chunkSize, int totalChunks);
    /**
     * 上传分片
     * @param uid 用户ID
     * @param clientId 上传客户端ID
     * @param chunkNumber 分片序号
     * @param file 文件
     */
    void uploadChunk(String uid, String clientId, int chunkNumber, MultipartFile file);
    /**
     * 获取上传进度
     * @param clientId 上传客户端ID
     * @return 上传进度
     */
    ProgressVO getUploadProgress(String clientId);
    /**
     * 取消上传
     * @param uid 用户ID
     * @param clientId 上传客户端ID
     * @return 是否取消成功
     */
    boolean cancelUpload(String uid,String clientId);
    /**
     * 完成上传
     * @param uid 用户ID
     * @param clientId 上传客户端ID
     */
    void completeUpload(String uid,String clientId);
    /**
     * 暂停上传
     * @param clientId 上传客户端ID
     */
    void pauseUpload(String clientId);
    /**
     * 恢复上传
     * @param clientId 上传客户端ID
     * @return 恢复上传结果
     */
    ResumeUploadVO resumeUpload(String clientId);
    /**
     * 检查上传状态
     * @param clientId 上传客户端ID
     * @return 上传状态
     */
    FileUploadStatusVO checkFileStatus(String clientId);
}
