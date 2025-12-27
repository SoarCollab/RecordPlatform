package cn.flying.service;

import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 分享审计服务接口
 * <p>
 * 提供分享访问日志记录和查询功能，以及文件溯源链路查询。
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
public interface ShareAuditService {

    // ==================== 访问日志记录 ====================

    /**
     * 记录分享查看操作
     *
     * @param shareCode   分享码
     * @param actorUserId 操作者用户ID（匿名访问为null）
     * @param ip          IP地址
     * @param userAgent   User-Agent
     */
    void logShareView(String shareCode, Long actorUserId, String ip, String userAgent);

    /**
     * 记录分享下载操作
     *
     * @param shareCode   分享码
     * @param actorUserId 操作者用户ID（匿名访问为null）
     * @param fileHash    文件哈希
     * @param fileName    文件名
     * @param ip          IP地址
     */
    void logShareDownload(String shareCode, Long actorUserId, String fileHash, String fileName, String ip);

    /**
     * 记录分享保存操作
     *
     * @param shareCode   分享码
     * @param actorUserId 操作者用户ID
     * @param fileHash    文件哈希
     * @param fileName    文件名
     * @param ip          IP地址
     */
    void logShareSave(String shareCode, Long actorUserId, String fileHash, String fileName, String ip);

    // ==================== 访问日志查询（管理员专用）====================

    /**
     * 获取分享的访问日志（分页）
     * <p>管理员专用接口，通过 @PreAuthorize("isAdmin()") 控制访问权限</p>
     *
     * @param shareCode 分享码
     * @param page      分页参数
     * @return 访问日志分页
     */
    IPage<ShareAccessLogVO> getShareAccessLogs(String shareCode, Page<?> page);

    /**
     * 获取分享的访问统计
     * <p>管理员专用接口，通过 @PreAuthorize("isAdmin()") 控制访问权限</p>
     *
     * @param shareCode 分享码
     * @return 访问统计
     */
    ShareAccessStatsVO getShareAccessStats(String shareCode);

    // ==================== 文件溯源（管理员专用）====================

    /**
     * 获取文件溯源信息
     * <p>管理员专用接口，通过 @PreAuthorize("isAdmin()") 控制访问权限</p>
     *
     * @param fileId 文件ID
     * @return 溯源信息
     */
    FileProvenanceVO getFileProvenance(Long fileId);
}
