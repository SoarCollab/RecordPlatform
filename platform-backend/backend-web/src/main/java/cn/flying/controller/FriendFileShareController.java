package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.vo.friend.FriendFileShareDetailVO;
import cn.flying.dao.vo.friend.FriendShareVO;
import cn.flying.service.FriendFileShareService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 好友文件分享控制器
 */
@RestController
@RequestMapping("/api/v1/friend-shares")
@Tag(name = "好友文件分享", description = "好友间直接分享文件的操作")
@Validated
public class FriendFileShareController {

    @Resource
    private FriendFileShareService friendFileShareService;

    @PostMapping
    @Operation(summary = "分享文件给好友")
    @OperationLog(module = "好友分享", operationType = "新增", description = "分享文件给好友")
    public Result<FriendFileShareDetailVO> shareToFriend(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody FriendShareVO vo) {
        FriendFileShare share = friendFileShareService.shareToFriend(userId, vo);
        FriendFileShareDetailVO result = friendFileShareService.getShareDetail(userId, IdUtils.toExternalId(share.getId()));
        return Result.success(result);
    }

    @GetMapping("/received")
    @Operation(summary = "获取收到的好友分享列表")
    public Result<IPage<FriendFileShareDetailVO>> getReceivedShares(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FriendFileShareDetailVO> result = friendFileShareService.getReceivedShares(userId, page);
        return Result.success(result);
    }

    @GetMapping("/sent")
    @Operation(summary = "获取发送的好友分享列表")
    public Result<IPage<FriendFileShareDetailVO>> getSentShares(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FriendFileShareDetailVO> result = friendFileShareService.getSentShares(userId, page);
        return Result.success(result);
    }

    @GetMapping("/{shareId}")
    @Operation(summary = "获取好友分享详情")
    public Result<FriendFileShareDetailVO> getShareDetail(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        FriendFileShareDetailVO result = friendFileShareService.getShareDetail(userId, shareId);
        return Result.success(result);
    }

    @PostMapping("/{shareId}/read")
    @Operation(summary = "标记分享为已读")
    public Result<String> markAsRead(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        friendFileShareService.markAsRead(userId, shareId);
        return Result.success();
    }

    @DeleteMapping("/{shareId}")
    @Operation(summary = "取消分享")
    @OperationLog(module = "好友分享", operationType = "删除", description = "取消好友分享")
    public Result<String> cancelShare(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        friendFileShareService.cancelShare(userId, shareId);
        return Result.success();
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取未读好友分享数量")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = friendFileShareService.getUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }
}
