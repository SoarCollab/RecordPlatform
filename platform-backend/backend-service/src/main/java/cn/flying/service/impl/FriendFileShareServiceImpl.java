package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FriendFileShareMapper;
import cn.flying.dao.vo.friend.FriendFileShareDetailVO;
import cn.flying.dao.vo.friend.FriendShareVO;
import cn.flying.service.AccountService;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.FriendService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FriendFileShareServiceImpl extends ServiceImpl<FriendFileShareMapper, FriendFileShare>
        implements FriendFileShareService {

    @Resource
    private AccountService accountService;

    @Lazy
    @Resource
    private FriendService friendService;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public FriendFileShare shareToFriend(Long sharerId, FriendShareVO vo) {
        Long friendId = IdUtils.fromExternalId(vo.getFriendId());

        // 检查是否是好友
        if (!friendService.areFriends(sharerId, friendId)) {
            throw new GeneralException(ResultEnum.NOT_FRIENDS);
        }

        // 检查好友是否存在
        Account friend = accountService.findAccountById(friendId);
        if (friend == null) {
            throw new GeneralException(ResultEnum.USER_NOT_EXIST);
        }

        // 验证文件所有权
        List<String> fileHashes = vo.getFileHashes().stream().distinct().toList();
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, sharerId)
                .in(File::getFileHash, fileHashes);
        long ownedCount = fileMapper.selectCount(wrapper);
        if (ownedCount != fileHashes.size()) {
            // 安全策略：不泄露文件存在性/归属，统一视为文件不存在
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }

        // 序列化文件哈希列表
        String fileHashesJson;
        try {
            fileHashesJson = objectMapper.writeValueAsString(fileHashes);
        } catch (JsonProcessingException e) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件列表序列化失败");
        }

        // 创建分享记录
        FriendFileShare share = new FriendFileShare()
                .setSharerId(sharerId)
                .setFriendId(friendId)
                .setFileHashes(fileHashesJson)
                .setMessage(vo.getMessage())
                .setIsRead(0)
                .setStatus(FriendFileShare.STATUS_ACTIVE);

        this.save(share);
        log.info("好友文件分享: id={}, sharer={}, friend={}, files={}", share.getId(), sharerId, friendId, fileHashes.size());

        // SSE 通知好友
        Long tenantId = TenantContext.getTenantId();
        Account sharer = accountService.findAccountById(sharerId);
        sseEmitterManager.sendToUser(tenantId, friendId, SseEvent.of(SseEventType.FRIEND_SHARE, Map.of(
                "shareId", IdUtils.toExternalId(share.getId()),
                "sharerName", sharer != null ? sharer.getUsername() : "未知用户",
                "fileCount", fileHashes.size(),
                "message", vo.getMessage() != null ? vo.getMessage() : ""
        )));

        return share;
    }

    @Override
    public IPage<FriendFileShareDetailVO> getReceivedShares(Long userId, Page<?> page) {
        LambdaQueryWrapper<FriendFileShare> wrapper = new LambdaQueryWrapper<FriendFileShare>()
                .eq(FriendFileShare::getFriendId, userId)
                .eq(FriendFileShare::getStatus, FriendFileShare.STATUS_ACTIVE)
                .orderByDesc(FriendFileShare::getCreateTime);

        Page<FriendFileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        IPage<FriendFileShare> result = this.page(sharePage, wrapper);

        return result.convert(share -> convertToVO(share, true));
    }

    @Override
    public IPage<FriendFileShareDetailVO> getSentShares(Long userId, Page<?> page) {
        LambdaQueryWrapper<FriendFileShare> wrapper = new LambdaQueryWrapper<FriendFileShare>()
                .eq(FriendFileShare::getSharerId, userId)
                .orderByDesc(FriendFileShare::getCreateTime);

        Page<FriendFileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        IPage<FriendFileShare> result = this.page(sharePage, wrapper);

        return result.convert(share -> convertToVO(share, false));
    }

    @Override
    @Transactional
    public void markAsRead(Long userId, String shareId) {
        Long sId = IdUtils.fromExternalId(shareId);
        FriendFileShare share = this.getById(sId);

        if (share == null) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_NOT_FOUND);
        }

        // 仅接收者可以标记已读
        if (!share.getFriendId().equals(userId)) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_UNAUTHORIZED);
        }

        // 幂等：已读则直接返回成功
        if (share.getIsRead() != null && share.getIsRead() == 1) {
            return;
        }

        int updated = baseMapper.markAsRead(sId, userId, new Date(), TenantContext.getTenantId());
        if (updated > 0) {
            log.info("标记好友分享已读: shareId={}, userId={}", sId, userId);
        }
    }

    @Override
    @Transactional
    public void cancelShare(Long userId, String shareId) {
        Long sId = IdUtils.fromExternalId(shareId);
        FriendFileShare share = this.getById(sId);

        if (share == null) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_NOT_FOUND);
        }

        if (!share.getSharerId().equals(userId)) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_UNAUTHORIZED);
        }

        share.setStatus(FriendFileShare.STATUS_CANCELLED);
        this.updateById(share);
        log.info("取消好友分享: shareId={}", sId);
    }

    @Override
    public int getUnreadCount(Long userId) {
        return baseMapper.countUnread(userId, TenantContext.getTenantId());
    }

    @Override
    public FriendFileShareDetailVO getShareDetail(Long userId, String shareId) {
        Long sId = IdUtils.fromExternalId(shareId);
        FriendFileShare share = this.getById(sId);

        if (share == null) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_NOT_FOUND);
        }

        // 只有分享者或接收者可以查看
        if (!share.getSharerId().equals(userId) && !share.getFriendId().equals(userId)) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_UNAUTHORIZED);
        }

        // 已取消的分享，接收者不可查看（分享者可以查看自己取消的记录）
        if (!share.isActive() && !share.getSharerId().equals(userId)) {
            throw new GeneralException(ResultEnum.FRIEND_SHARE_NOT_FOUND);
        }

        return convertToVO(share, share.getFriendId().equals(userId));
    }

    @Override
    public Long getSharerIdForFile(Long userId, String fileHash) {
        return baseMapper.findSharerIdForFile(userId, fileHash, TenantContext.getTenantId());
    }

    private FriendFileShareDetailVO convertToVO(FriendFileShare share, boolean isReceiver) {
        Account sharer = accountService.findAccountById(share.getSharerId());
        Account friend = accountService.findAccountById(share.getFriendId());

        List<String> fileHashes = parseFileHashes(share.getFileHashes());
        List<String> fileNames = getFileNames(share.getSharerId(), fileHashes);

        FriendFileShareDetailVO vo = new FriendFileShareDetailVO()
                .setId(IdUtils.toExternalId(share.getId()))
                .setSharerId(IdUtils.toExternalId(share.getSharerId()))
                .setFriendId(IdUtils.toExternalId(share.getFriendId()))
                .setFileHashes(fileHashes)
                .setFileNames(fileNames)
                .setFileCount(fileHashes.size())
                .setMessage(share.getMessage())
                .setIsRead(share.getIsRead() != null && share.getIsRead() == 1)
                .setCreateTime(share.getCreateTime())
                .setReadTime(share.getReadTime());

        if (sharer != null) {
            vo.setSharerUsername(sharer.getUsername())
                    .setSharerAvatar(sharer.getAvatar());
        }
        if (friend != null) {
            vo.setFriendUsername(friend.getUsername());
        }

        return vo;
    }

    private List<String> parseFileHashes(String fileHashesJson) {
        if (fileHashesJson == null || fileHashesJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(fileHashesJson, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析文件哈希列表失败: {}", fileHashesJson);
            return Collections.emptyList();
        }
    }

    private List<String> getFileNames(Long ownerId, List<String> fileHashes) {
        if (fileHashes.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, ownerId)
                .in(File::getFileHash, fileHashes)
                .select(File::getFileHash, File::getFileName);

        List<File> files = fileMapper.selectList(wrapper);
        Map<String, String> hashToName = files.stream()
                .collect(Collectors.toMap(File::getFileHash, File::getFileName, (a, b) -> a));

        return fileHashes.stream()
                .map(hash -> hashToName.getOrDefault(hash, "未知文件"))
                .collect(Collectors.toList());
    }
}
