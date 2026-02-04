package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
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
import cn.flying.dao.vo.admin.AdminFileDetailVO;
import cn.flying.dao.vo.admin.AdminFileQueryParam;
import cn.flying.dao.vo.admin.AdminFileVO;
import cn.flying.dao.vo.admin.AdminShareQueryParam;
import cn.flying.dao.vo.admin.AdminShareVO;
import cn.flying.dao.vo.file.FileProvenanceVO.ProvenanceNode;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.service.FileAdminService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 管理员文件审计服务实现
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Service
public class FileAdminServiceImpl implements FileAdminService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileAdminServiceImpl.class);

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private FileSourceMapper fileSourceMapper;

    @Resource
    private ShareAccessLogMapper shareAccessLogMapper;

    @Resource
    private AccountMapper accountMapper;

    // ==================== 文件管理 ====================

    @Override
    public IPage<AdminFileVO> getAllFiles(AdminFileQueryParam param, Page<?> page) {
        Long tenantId = TenantContext.getTenantId();

        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getTenantId, tenantId)
                .orderByDesc(File::getCreateTime);

        // 关键词搜索
        if (StringUtils.hasText(param.getKeyword())) {
            wrapper.and(w -> w
                    .like(File::getFileName, param.getKeyword())
                    .or()
                    .like(File::getFileHash, param.getKeyword()));
        }

        // 状态过滤
        if (param.getStatus() != null) {
            wrapper.eq(File::getStatus, param.getStatus());
        }

        // 是否仅原始文件
        if (Boolean.TRUE.equals(param.getOriginalOnly())) {
            wrapper.isNull(File::getOrigin);
        }

        // 是否仅分享保存的文件
        if (Boolean.TRUE.equals(param.getSharedOnly())) {
            wrapper.isNotNull(File::getOrigin);
        }

        // 分页查询
        Page<File> filePage = new Page<>(page.getCurrent(), page.getSize());
        IPage<File> result = fileMapper.selectPage(filePage, wrapper);

        // 批量获取用户名
        Map<Long, String> userNameCache = new HashMap<>();

        // 批量预加载原始文件和来源记录，避免 N+1 查询
        List<File> files = result.getRecords();
        List<Long> originIds = files.stream()
                .map(File::getOrigin)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, File> originFileMap = new HashMap<>();
        if (!originIds.isEmpty()) {
            LambdaQueryWrapper<File> originWrapper = new LambdaQueryWrapper<File>()
                    .in(File::getId, originIds);
            fileMapper.selectList(originWrapper).forEach(f -> originFileMap.put(f.getId(), f));
        }

        List<Long> fileIds = files.stream()
                .filter(f -> f.getOrigin() != null)
                .map(File::getId)
                .toList();
        Map<Long, FileSource> fileSourceMap = new HashMap<>();
        if (!fileIds.isEmpty()) {
            LambdaQueryWrapper<FileSource> sourceWrapper = new LambdaQueryWrapper<FileSource>()
                    .in(FileSource::getFileId, fileIds)
                    .eq(FileSource::getTenantId, tenantId);
            fileSourceMapper.selectList(sourceWrapper).forEach(s -> fileSourceMap.put(s.getFileId(), s));
        }

        return result.convert(file -> convertToAdminFileVO(file, userNameCache, originFileMap, fileSourceMap));
    }

    @Override
    public AdminFileDetailVO getFileDetail(String fileId) {
        Long id = IdUtils.fromExternalId(fileId);
        Long tenantId = TenantContext.getTenantId();
        File file = fileMapper.selectById(id);
        if (file == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在");
        }

        Map<Long, String> userNameCache = new HashMap<>();
        AdminFileDetailVO detail = AdminFileDetailVO.builder()
                .id(IdUtils.toExternalId(file.getId()))
                .fileName(file.getFileName())
                .fileHash(file.getFileHash())
                .fileSize(file.getFileSize())
                .contentType(file.getContentType())
                .status(file.getStatus())
                .statusDesc(getStatusDesc(file.getStatus()))
                .createTime(file.getCreateTime())
                .ownerId(String.valueOf(file.getUid()))
                .ownerName(getUserName(file.getUid(), userNameCache))
                .transactionHash(file.getTransactionHash())
                .isOriginal(file.getOrigin() == null)
                .build();

        // 如果是分享保存的文件，获取溯源信息
        if (file.getOrigin() != null) {
            File originFile = fileMapper.selectById(file.getOrigin());
            if (originFile != null) {
                detail.setOriginOwnerId(String.valueOf(originFile.getUid()));
                detail.setOriginOwnerName(getUserName(originFile.getUid(), userNameCache));
            }

            if (file.getSharedFromUserId() != null) {
                detail.setSharedFromUserId(String.valueOf(file.getSharedFromUserId()));
                detail.setSharedFromUserName(getUserName(file.getSharedFromUserId(), userNameCache));
            }

            // 获取完整溯源链路
            FileSource source = fileSourceMapper.selectByFileId(id, tenantId);
            if (source != null) {
                detail.setDepth(source.getDepth());
                detail.setSaveShareCode(source.getShareCode());
                detail.setProvenanceChain(buildProvenanceChain(id, file, originFile, tenantId, userNameCache));
            }
        } else {
            detail.setDepth(0);
            detail.setProvenanceChain(List.of());
        }

        // 获取引用计数
        Long refCount = fileMapper.countActiveFilesByHash(file.getFileHash(), id);
        detail.setRefCount(refCount != null ? refCount.intValue() : 0);

        // 获取相关分享列表
        detail.setRelatedShares(getRelatedShares(file.getFileHash(), userNameCache));

        // 获取最近访问日志（限制10条）
        detail.setRecentAccessLogs(getRecentAccessLogs(file.getFileHash(), tenantId, 10, userNameCache));

        return detail;
    }

    @Override
    @Transactional
    public void updateFileStatus(String fileId, Integer status, String reason) {
        Long id = IdUtils.fromExternalId(fileId);
        File file = fileMapper.selectById(id);
        if (file == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在");
        }

        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getId, id)
                .set(File::getStatus, status);

        fileMapper.update(null, wrapper);
        LOGGER.info("管理员更新文件状态: fileId={}, oldStatus={}, newStatus={}, reason={}",
                fileId, file.getStatus(), status, reason);
    }

    @Override
    @Transactional
    public void forceDeleteFile(String fileId, String reason) {
        Long id = IdUtils.fromExternalId(fileId);
        Long tenantId = TenantContext.getTenantId();

        File file = fileMapper.selectById(id);
        if (file == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件不存在");
        }

        // 删除关联的 FileSource 记录
        LambdaQueryWrapper<FileSource> sourceWrapper = new LambdaQueryWrapper<FileSource>()
                .eq(FileSource::getFileId, id)
                .eq(FileSource::getTenantId, tenantId);
        fileSourceMapper.delete(sourceWrapper);

        // 物理删除文件记录
        int deleted = fileMapper.physicalDeleteById(id, tenantId);
        if (deleted > 0) {
            LOGGER.info("管理员强制删除文件: fileId={}, fileName={}, reason={}",
                    fileId, file.getFileName(), reason);
        }
    }

    // ==================== 分享管理 ====================

    @Override
    public IPage<AdminShareVO> getAllShares(AdminShareQueryParam param, Page<?> page) {
        Long tenantId = TenantContext.getTenantId();

        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(FileShare::getTenantId, tenantId)
                .orderByDesc(FileShare::getCreateTime);

        // 关键词搜索
        if (StringUtils.hasText(param.getKeyword())) {
            wrapper.and(w -> w
                    .like(FileShare::getShareCode, param.getKeyword())
                    .or()
                    .like(FileShare::getFileHashes, param.getKeyword()));
        }

        // 状态过滤
        if (param.getStatus() != null) {
            wrapper.eq(FileShare::getStatus, param.getStatus());
        }

        // 分享类型过滤
        if (param.getShareType() != null) {
            wrapper.eq(FileShare::getShareType, param.getShareType());
        }

        // 分页查询
        Page<FileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        IPage<FileShare> result = fileShareMapper.selectPage(sharePage, wrapper);

        // 批量获取用户名
        Map<Long, String> userNameCache = new HashMap<>();

        // 批量查询访问统计，避免 N+1 查询
        List<String> shareCodes = result.getRecords().stream()
                .map(FileShare::getShareCode)
                .toList();
        Map<String, Map<Integer, Long>> statsMap = new HashMap<>();
        Map<String, Long> uniqueActorsMap = new HashMap<>();

        if (!shareCodes.isEmpty()) {
            // 批量查询各类型访问次数
            List<Map<String, Object>> statsList = shareAccessLogMapper.batchCountByShareCodes(shareCodes, tenantId);
            for (Map<String, Object> row : statsList) {
                String code = (String) row.get("share_code");
                Object actionTypeObj = row.get("action_type");
                Object cntObj = row.get("cnt");
                if (code == null || !(actionTypeObj instanceof Number) || !(cntObj instanceof Number)) {
                    LOGGER.warn("Invalid row in batchCountByShareCodes: {}", row);
                    continue;
                }
                Integer actionType = ((Number) actionTypeObj).intValue();
                Long cnt = ((Number) cntObj).longValue();
                statsMap.computeIfAbsent(code, k -> new HashMap<>()).put(actionType, cnt);
            }

            // 批量查询独立访问者数
            List<Map<String, Object>> actorsList = shareAccessLogMapper.batchCountDistinctActors(shareCodes, tenantId);
            for (Map<String, Object> row : actorsList) {
                String code = (String) row.get("share_code");
                Object uniqueActorsObj = row.get("unique_actors");
                if (code == null || !(uniqueActorsObj instanceof Number)) {
                    LOGGER.warn("Invalid row in batchCountDistinctActors: {}", row);
                    continue;
                }
                Long uniqueActors = ((Number) uniqueActorsObj).longValue();
                uniqueActorsMap.put(code, uniqueActors);
            }
        }

        return result.convert(share -> convertToAdminShareVO(share, userNameCache, statsMap, uniqueActorsMap));
    }

    @Override
    @Transactional
    public void forceCancelShare(String shareCode, String reason) {
        FileShare share = fileShareMapper.selectByShareCode(shareCode);
        if (share == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分享不存在");
        }

        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareCode, shareCode)
                .set(FileShare::getStatus, FileShare.STATUS_CANCELLED)
                .set(FileShare::getUpdateTime, new Date());

        fileShareMapper.update(null, wrapper);
        LOGGER.info("管理员强制取消分享: shareCode={}, reason={}", shareCode, reason);
    }

    // ==================== 工具方法 ====================

    private AdminFileVO convertToAdminFileVO(File file, Map<Long, String> userNameCache,
                                             Map<Long, File> originFileMap, Map<Long, FileSource> fileSourceMap) {
        AdminFileVO vo = AdminFileVO.builder()
                .id(IdUtils.toExternalId(file.getId()))
                .fileName(file.getFileName())
                .fileHash(file.getFileHash())
                .fileSize(file.getFileSize())
                .contentType(file.getContentType())
                .status(file.getStatus())
                .statusDesc(getStatusDesc(file.getStatus()))
                .ownerId(String.valueOf(file.getUid()))
                .ownerName(getUserName(file.getUid(), userNameCache))
                .transactionHash(file.getTransactionHash())
                .isOriginal(file.getOrigin() == null)
                .createTime(file.getCreateTime())
                .build();

        if (file.getOrigin() != null) {
            File originFile = originFileMap.get(file.getOrigin());
            if (originFile != null) {
                vo.setOriginOwnerId(String.valueOf(originFile.getUid()));
                vo.setOriginOwnerName(getUserName(originFile.getUid(), userNameCache));
            }

            if (file.getSharedFromUserId() != null) {
                vo.setSharedFromUserId(String.valueOf(file.getSharedFromUserId()));
                vo.setSharedFromUserName(getUserName(file.getSharedFromUserId(), userNameCache));
            }

            FileSource source = fileSourceMap.get(file.getId());
            if (source != null) {
                vo.setDepth(source.getDepth());
            }
        } else {
            vo.setDepth(0);
        }

        return vo;
    }

    private AdminShareVO convertToAdminShareVO(FileShare share, Map<Long, String> userNameCache,
                                               Map<String, Map<Integer, Long>> statsMap,
                                               Map<String, Long> uniqueActorsMap) {
        List<String> fileHashes = parseFileHashes(share.getFileHashes());
        List<String> fileNames = getFileNames(fileHashes);

        // 从预加载的统计数据中获取访问次数
        Map<Integer, Long> stats = statsMap.getOrDefault(share.getShareCode(), Map.of());
        Long viewCount = stats.getOrDefault(ShareAccessLog.ACTION_VIEW, 0L);
        Long downloadCount = stats.getOrDefault(ShareAccessLog.ACTION_DOWNLOAD, 0L);
        Long saveCount = stats.getOrDefault(ShareAccessLog.ACTION_SAVE, 0L);
        Long uniqueActors = uniqueActorsMap.getOrDefault(share.getShareCode(), 0L);

        return AdminShareVO.builder()
                .id(String.valueOf(share.getId()))
                .shareCode(share.getShareCode())
                .sharerId(String.valueOf(share.getUserId()))
                .sharerName(getUserName(share.getUserId(), userNameCache))
                .shareType(share.getShareType())
                .shareTypeDesc(share.getShareType() == 0 ? "公开分享" : "私密分享")
                .status(share.getStatus())
                .statusDesc(getShareStatusDesc(share.getStatus()))
                .fileCount(fileHashes.size())
                .fileHashes(fileHashes)
                .fileNames(fileNames)
                .accessCount(share.getAccessCount())
                .hasPassword(share.getShareType() != null && share.getShareType() == 1)
                .createTime(share.getCreateTime())
                .expireTime(share.getExpireTime())
                .viewCount(viewCount)
                .downloadCount(downloadCount)
                .saveCount(saveCount)
                .uniqueActors(uniqueActors)
                .build();
    }

    private List<ProvenanceNode> buildProvenanceChain(Long fileId, File file, File originFile, Long tenantId, Map<Long, String> userNameCache) {
        List<ProvenanceNode> chain = new ArrayList<>();

        // 添加原始节点
        if (originFile != null) {
            chain.add(new ProvenanceNode(
                    String.valueOf(originFile.getUid()),
                    getUserName(originFile.getUid(), userNameCache),
                    String.valueOf(originFile.getId()),
                    0,
                    null,
                    originFile.getCreateTime()
            ));
        }

        // 获取中间节点
        List<FileSource> chainSources = fileSourceMapper.selectProvenanceChain(fileId, tenantId);
        for (FileSource source : chainSources) {
            chain.add(new ProvenanceNode(
                    String.valueOf(source.getSourceUserId()),
                    getUserName(source.getSourceUserId(), userNameCache),
                    String.valueOf(source.getSourceFileId()),
                    source.getDepth(),
                    source.getShareCode(),
                    source.getCreateTime()
            ));
        }

        // 添加当前节点
        FileSource currentSource = fileSourceMapper.selectByFileId(fileId, tenantId);
        chain.add(new ProvenanceNode(
                String.valueOf(file.getUid()),
                getUserName(file.getUid(), userNameCache),
                String.valueOf(file.getId()),
                currentSource != null ? currentSource.getDepth() : 0,
                currentSource != null ? currentSource.getShareCode() : null,
                file.getCreateTime()
        ));

        return chain;
    }

    private List<AdminFileDetailVO.RelatedShare> getRelatedShares(String fileHash, Map<Long, String> userNameCache) {
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(FileShare::getTenantId, tenantId)
                .like(FileShare::getFileHashes, fileHash)
                .orderByDesc(FileShare::getCreateTime);

        List<FileShare> shares = fileShareMapper.selectList(wrapper);
        return shares.stream()
                .map(share -> AdminFileDetailVO.RelatedShare.builder()
                        .shareCode(share.getShareCode())
                        .sharerName(getUserName(share.getUserId(), userNameCache))
                        .shareType(share.getShareType())
                        .status(share.getStatus())
                        .createTime(share.getCreateTime())
                        .expireTime(share.getExpireTime())
                        .accessCount(share.getAccessCount())
                        .build())
                .toList();
    }

    private List<ShareAccessLogVO> getRecentAccessLogs(String fileHash, Long tenantId, int limit, Map<Long, String> userNameCache) {
        LambdaQueryWrapper<ShareAccessLog> wrapper = new LambdaQueryWrapper<ShareAccessLog>()
                .eq(ShareAccessLog::getFileHash, fileHash)
                .eq(ShareAccessLog::getTenantId, tenantId)
                .orderByDesc(ShareAccessLog::getAccessTime);

        Page<ShareAccessLog> page = new Page<>(1, limit);
        IPage<ShareAccessLog> logPage = shareAccessLogMapper.selectPage(page, wrapper);

        return logPage.getRecords().stream()
                .map(logEntry -> new ShareAccessLogVO(
                        String.valueOf(logEntry.getId()),
                        logEntry.getShareCode(),
                        logEntry.getActionType(),
                        ShareAccessLogVO.getActionTypeDesc(logEntry.getActionType()),
                        logEntry.getActorUserId() != null ? String.valueOf(logEntry.getActorUserId()) : null,
                        logEntry.getActorUserId() != null ?
                                getUserName(logEntry.getActorUserId(), userNameCache) : "匿名用户",
                        logEntry.getActorIp(),
                        logEntry.getFileHash(),
                        logEntry.getFileName(),
                        logEntry.getAccessTime()
                ))
                .toList();
    }

    private String getUserName(Long userId, Map<Long, String> cache) {
        if (userId == null) return "未知用户";
        return cache.computeIfAbsent(userId, id -> {
            Account account = accountMapper.selectById(id);
            return account != null ? account.getUsername() : "未知用户";
        });
    }

    private List<String> parseFileHashes(String fileHashesJson) {
        if (!StringUtils.hasText(fileHashesJson)) {
            return List.of();
        }
        try {
            return JsonConverter.parse(fileHashesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> getFileNames(List<String> fileHashes) {
        if (fileHashes.isEmpty()) return List.of();
        Long tenantId = TenantContext.getTenantId();
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getTenantId, tenantId)
                .in(File::getFileHash, fileHashes)
                .select(File::getFileName);
        return fileMapper.selectList(wrapper).stream()
                .map(File::getFileName)
                .distinct()
                .toList();
    }

    private String getStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "处理中";
            case 1 -> "已完成";
            case 2 -> "已删除";
            case -1 -> "失败";
            default -> "未知";
        };
    }

    private String getShareStatusDesc(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "已取消";
            case 1 -> "有效";
            case 2 -> "已过期";
            default -> "未知";
        };
    }
}
