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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 好友文件分享控制器。
 */
@RestController
@RequestMapping("/api/v1/friend-shares")
@Tag(name = "好友文件分享", description = "好友间直接分享文件的操作")
@Validated
public class FriendFileShareController {

    @Resource
    private FriendFileShareService friendFileShareService;

    /**
     * 分享文件给好友。
     *
     * @param userId 当前用户 ID
     * @param vo     分享参数
     * @return 分享详情
     */
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

    /**
     * 获取收到的好友分享列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
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

    /**
     * 获取发送的好友分享列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
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

    /**
     * 获取好友分享详情。
     *
     * @param userId  当前用户 ID
     * @param shareId 分享外部 ID
     * @return 分享详情
     */
    @GetMapping("/{shareId}")
    @Operation(summary = "获取好友分享详情")
    public Result<FriendFileShareDetailVO> getShareDetail(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        FriendFileShareDetailVO result = friendFileShareService.getShareDetail(userId, shareId);
        return Result.success(result);
    }

    /**
     * 标记好友分享已读状态（REST 新路径）。
     *
     * @param userId  当前用户 ID
     * @param shareId 分享外部 ID
     * @return 操作结果
     */
    @PutMapping("/{shareId}/read-status")
    @Operation(summary = "标记分享为已读（REST）")
    public Result<String> updateReadStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        friendFileShareService.markAsRead(userId, shareId);
        return Result.success();
    }

    /**
     * 取消好友分享。
     *
     * @param userId  当前用户 ID
     * @param shareId 分享外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/{shareId}")
    @Operation(summary = "取消分享")
    @OperationLog(module = "好友分享", operationType = "删除", description = "取消好友分享")
    public Result<String> cancelShare(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享ID") @PathVariable String shareId) {
        friendFileShareService.cancelShare(userId, shareId);
        return Result.success();
    }

    /**
     * 获取未读好友分享数量。
     *
     * @param userId 当前用户 ID
     * @return 未读数量
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读好友分享数量")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = friendFileShareService.getUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }
}
