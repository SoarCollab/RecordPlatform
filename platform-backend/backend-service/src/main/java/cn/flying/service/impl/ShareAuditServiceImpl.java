package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.dto.FileSource;
import cn.flying.dao.dto.ShareAccessLog;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.mapper.ShareAccessLogMapper;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.FileProvenanceVO.ProvenanceNode;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.service.ShareAuditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分享审计服务实现
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Slf4j
@Service
public class ShareAuditServiceImpl implements ShareAuditService {

    @Resource
    private ShareAccessLogMapper shareAccessLogMapper;

    @Resource
    private FileSourceMapper fileSourceMapper;

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private AccountMapper accountMapper;

    // ==================== 访问日志记录 ====================

    @Override
    @Async
    public void logShareView(String shareCode, Long actorUserId, String ip, String userAgent) {
        // 异步方法中需要显式设置租户上下文，因为ThreadLocal在异步线程中会丢失
        // 访问日志使用分享所有者的租户ID
        try {
            FileShare share = TenantContext.runWithoutIsolation(() ->
                    fileShareMapper.selectByShareCode(shareCode));
            if (share == null) {
                log.warn("记录分享查看日志时分享不存在: {}", shareCode);
                return;
            }

            Long tenantId = share.getTenantId();
            TenantContext.runWithTenant(tenantId, () -> {
                ShareAccessLog logEntry = new ShareAccessLog()
                        .setShareCode(shareCode)
                        .setShareOwnerId(share.getUserId())
                        .setActionType(ShareAccessLog.ACTION_VIEW)
                        .setActorUserId(actorUserId)
                        .setActorIp(ip)
                        .setActorUa(truncateUserAgent(userAgent))
                        .setAccessTime(new Date())
                        .setTenantId(tenantId);

                shareAccessLogMapper.insert(logEntry);
                log.debug("记录分享查看: shareCode={}, actor={}", shareCode, actorUserId);
            });
        } catch (Exception e) {
            log.error("记录分享查看日志失败: {}", e.getMessage());
        }
    }

    @Override
    @Async
    public void logShareDownload(String shareCode, Long actorUserId, String fileHash, String fileName, String ip) {
        try {
            FileShare share = TenantContext.runWithoutIsolation(() ->
                    fileShareMapper.selectByShareCode(shareCode));
            if (share == null) {
                log.warn("记录分享下载日志时分享不存在: {}", shareCode);
                return;
            }

            Long tenantId = share.getTenantId();
            TenantContext.runWithTenant(tenantId, () -> {
                ShareAccessLog logEntry = new ShareAccessLog()
                        .setShareCode(shareCode)
                        .setShareOwnerId(share.getUserId())
                        .setActionType(ShareAccessLog.ACTION_DOWNLOAD)
                        .setActorUserId(actorUserId)
                        .setActorIp(ip)
                        .setFileHash(fileHash)
                        .setFileName(fileName)
                        .setAccessTime(new Date())
                        .setTenantId(tenantId);

                shareAccessLogMapper.insert(logEntry);
                log.debug("记录分享下载: shareCode={}, fileHash={}, actor={}", shareCode, fileHash, actorUserId);
            });
        } catch (Exception e) {
            log.error("记录分享下载日志失败: {}", e.getMessage());
        }
    }

    @Override
    @Async
    public void logShareSave(String shareCode, Long actorUserId, String fileHash, String fileName, String ip) {
        try {
            FileShare share = TenantContext.runWithoutIsolation(() ->
                    fileShareMapper.selectByShareCode(shareCode));
            if (share == null) {
                log.warn("记录分享保存日志时分享不存在: {}", shareCode);
                return;
            }

            Long tenantId = share.getTenantId();
            TenantContext.runWithTenant(tenantId, () -> {
                ShareAccessLog logEntry = new ShareAccessLog()
                        .setShareCode(shareCode)
                        .setShareOwnerId(share.getUserId())
                        .setActionType(ShareAccessLog.ACTION_SAVE)
                        .setActorUserId(actorUserId)
                        .setActorIp(ip)
                        .setFileHash(fileHash)
                        .setFileName(fileName)
                        .setAccessTime(new Date())
                        .setTenantId(tenantId);

                shareAccessLogMapper.insert(logEntry);
                log.debug("记录分享保存: shareCode={}, fileHash={}, actor={}", shareCode, fileHash, actorUserId);
            });
        } catch (Exception e) {
            log.error("记录分享保存日志失败: {}", e.getMessage());
        }
    }

    // ==================== 访问日志查询（管理员专用）====================

    @Override
    public IPage<ShareAccessLogVO> getShareAccessLogs(String shareCode, Page<?> page) {
        // 验证分享存在性
        FileShare share = fileShareMapper.selectByShareCode(shareCode);
        if (share == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分享不存在");
        }

        // 查询访问日志
        LambdaQueryWrapper<ShareAccessLog> wrapper = new LambdaQueryWrapper<ShareAccessLog>()
                .eq(ShareAccessLog::getShareCode, shareCode)
                .orderByDesc(ShareAccessLog::getAccessTime);

        Page<ShareAccessLog> logPage = new Page<>(page.getCurrent(), page.getSize());
        IPage<ShareAccessLog> result = shareAccessLogMapper.selectPage(logPage, wrapper);

        // 批量获取用户名
        Map<Long, String> userNameCache = new HashMap<>();

            return result.convert(logEntry -> {
                String actorUserName = "匿名用户";
                if (logEntry.getActorUserId() != null) {
                    actorUserName = userNameCache.computeIfAbsent(logEntry.getActorUserId(), id -> {
                        Account account = accountMapper.selectById(id);
                        return account != null ? account.getUsername() : "未知用户";
                    });
                }

            return new ShareAccessLogVO(
                    String.valueOf(logEntry.getId()),
                    logEntry.getShareCode(),
                    logEntry.getActionType(),
                    ShareAccessLogVO.getActionTypeDesc(logEntry.getActionType()),
                    logEntry.getActorUserId() != null ? String.valueOf(logEntry.getActorUserId()) : null,
                    actorUserName,
                    logEntry.getActorIp(),
                    logEntry.getFileHash(),
                    logEntry.getFileName(),
                    logEntry.getAccessTime()
            );
        });
    }

    @Override
    public ShareAccessStatsVO getShareAccessStats(String shareCode) {
        // 验证分享存在性
        FileShare share = fileShareMapper.selectByShareCode(shareCode);
        if (share == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分享不存在");
        }

        Long tenantId = share.getTenantId();
        Long viewCount = shareAccessLogMapper.countByShareCodeAndAction(shareCode, ShareAccessLog.ACTION_VIEW, tenantId);
        Long downloadCount = shareAccessLogMapper.countByShareCodeAndAction(shareCode, ShareAccessLog.ACTION_DOWNLOAD, tenantId);
        Long saveCount = shareAccessLogMapper.countByShareCodeAndAction(shareCode, ShareAccessLog.ACTION_SAVE, tenantId);
        Long uniqueActors = shareAccessLogMapper.countDistinctActors(shareCode, tenantId);

        long safeViewCount = viewCount != null ? viewCount : 0L;
        long safeDownloadCount = downloadCount != null ? downloadCount : 0L;
        long safeSaveCount = saveCount != null ? saveCount : 0L;
        return new ShareAccessStatsVO(
                shareCode,
                safeViewCount,
                safeDownloadCount,
                safeSaveCount,
                uniqueActors != null ? uniqueActors : 0L,
                safeViewCount + safeDownloadCount + safeSaveCount
        );
    }

    // ==================== 文件溯源（管理员专用）====================

    @Override
    public FileProvenanceVO getFileProvenance(Long fileId) {
        // 查询文件
        File file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在");
        }

        Long tenantId = file.getTenantId();

        // 如果是原始文件
        if (file.getOrigin() == null) {
            return new FileProvenanceVO(
                    String.valueOf(file.getId()),
                    file.getFileHash(),
                    file.getFileName(),
                    true,
                    String.valueOf(file.getUid()),
                    getUserName(file.getUid()),
                    null,
                    null,
                    0,
                    null,
                    null,
                    List.of()
            );
        }

        // 查询来源信息
        FileSource source = fileSourceMapper.selectByFileId(fileId, tenantId);

        // 获取原始文件和原始上传者
        File originFile = fileMapper.selectById(file.getOrigin());
        String originUserName = originFile != null ? getUserName(originFile.getUid()) : "未知用户";
        String originUserId = originFile != null ? String.valueOf(originFile.getUid()) : null;

        // 获取直接分享者信息
        String sharedFromUserName = null;
        String sharedFromUserId = null;
        if (file.getSharedFromUserId() != null) {
            sharedFromUserId = String.valueOf(file.getSharedFromUserId());
            sharedFromUserName = getUserName(file.getSharedFromUserId());
        } else if (source != null) {
            sharedFromUserId = String.valueOf(source.getSourceUserId());
            sharedFromUserName = getUserName(source.getSourceUserId());
        }

        // 构建分享链路
        List<ProvenanceNode> chain = new ArrayList<>();
        int depth = 0;

        if (source != null) {
            // 使用递归查询获取完整链路
            List<FileSource> chainSources = fileSourceMapper.selectProvenanceChain(fileId, tenantId);
            depth = source.getDepth();

            // 添加原始节点
            if (originFile != null) {
                chain.add(new ProvenanceNode(
                        String.valueOf(originFile.getUid()),
                        originUserName,
                        String.valueOf(originFile.getId()),
                        0,
                        null,
                        originFile.getCreateTime()
                ));
            }

            // 添加中间节点
            for (FileSource chainSource : chainSources) {
                chain.add(new ProvenanceNode(
                        String.valueOf(chainSource.getSourceUserId()),
                        getUserName(chainSource.getSourceUserId()),
                        String.valueOf(chainSource.getSourceFileId()),
                        chainSource.getDepth(),
                        chainSource.getShareCode(),
                        chainSource.getCreateTime()
                ));
            }

            // 添加当前节点
            chain.add(new ProvenanceNode(
                    String.valueOf(file.getUid()),
                    getUserName(file.getUid()),
                    String.valueOf(file.getId()),
                    depth,
                    source.getShareCode(),
                    file.getCreateTime()
            ));
        }

        return new FileProvenanceVO(
                String.valueOf(file.getId()),
                file.getFileHash(),
                file.getFileName(),
                false,
                originUserId,
                originUserName,
                sharedFromUserId,
                sharedFromUserName,
                depth,
                file.getCreateTime(),
                source != null ? source.getShareCode() : null,
                chain
        );
    }

    // ==================== 工具方法 ====================

    private String getUserName(Long userId) {
        if (userId == null) return "未知用户";
        Account account = accountMapper.selectById(userId);
        return account != null ? account.getUsername() : "未知用户";
    }

    private String truncateUserAgent(String ua) {
        if (ua == null) return null;
        return ua.length() > 500 ? ua.substring(0, 500) : ua;
    }
}
