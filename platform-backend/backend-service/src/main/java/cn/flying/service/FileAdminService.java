package cn.flying.service;

import cn.flying.dao.vo.admin.AdminFileDetailVO;
import cn.flying.dao.vo.admin.AdminFileQueryParam;
import cn.flying.dao.vo.admin.AdminFileVO;
import cn.flying.dao.vo.admin.AdminShareQueryParam;
import cn.flying.dao.vo.admin.AdminShareVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 管理员文件审计服务接口
 * <p>
 * 提供管理员专用的文件和分享管理功能，包括：
 * <ul>
 *   <li>查看所有文件（跨用户）</li>
 *   <li>查看文件详情（含溯源链路、访问日志）</li>
 *   <li>修改文件状态、强制删除文件</li>
 *   <li>查看所有分享、强制取消分享</li>
 * </ul>
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
public interface FileAdminService {

    // ==================== 文件管理 ====================

    /**
     * 获取所有文件列表（分页）
     *
     * @param param 查询参数
     * @param page  分页参数
     * @return 文件列表分页
     */
    IPage<AdminFileVO> getAllFiles(AdminFileQueryParam param, Page<?> page);

    /**
     * 获取文件详情（含完整审计信息）
     *
     * @param fileId 文件ID（外部ID）
     * @return 文件详情
     */
    AdminFileDetailVO getFileDetail(String fileId);

    /**
     * 更新文件状态
     *
     * @param fileId 文件ID（外部ID）
     * @param status 新状态
     * @param reason 操作原因
     */
    void updateFileStatus(String fileId, Integer status, String reason);

    /**
     * 强制删除文件（物理删除）
     *
     * @param fileId 文件ID（外部ID）
     * @param reason 删除原因
     */
    void forceDeleteFile(String fileId, String reason);

    // ==================== 分享管理 ====================

    /**
     * 获取所有分享列表（分页）
     *
     * @param param 查询参数
     * @param page  分页参数
     * @return 分享列表分页
     */
    IPage<AdminShareVO> getAllShares(AdminShareQueryParam param, Page<?> page);

    /**
     * 强制取消分享
     *
     * @param shareCode 分享码
     * @param reason    取消原因
     */
    void forceCancelShare(String shareCode, String reason);
}
