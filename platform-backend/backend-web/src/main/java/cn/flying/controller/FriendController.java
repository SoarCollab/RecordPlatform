package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.vo.friend.*;
import cn.flying.service.FriendService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 好友管理控制器
 */
@RestController
@RequestMapping("/api/v1/friends")
@Tag(name = "好友管理", description = "好友请求、好友列表、用户搜索等操作")
@Validated
public class FriendController {

    @Resource
    private FriendService friendService;

    // ==================== 好友请求相关 ====================

    @PostMapping("/requests")
    @Operation(summary = "发送好友请求")
    @OperationLog(module = "好友", operationType = "新增", description = "发送好友请求")
    public Result<FriendRequestDetailVO> sendRequest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody SendFriendRequestVO vo) {
        FriendRequest request = friendService.sendRequest(userId, vo);

        FriendRequestDetailVO result = new FriendRequestDetailVO()
                .setId(IdUtils.toExternalId(request.getId()))
                .setRequesterId(IdUtils.toExternalId(request.getRequesterId()))
                .setAddresseeId(IdUtils.toExternalId(request.getAddresseeId()))
                .setMessage(request.getMessage())
                .setStatus(request.getStatus())
                .setCreateTime(request.getCreateTime());

        return Result.success(result);
    }

    @GetMapping("/requests/received")
    @Operation(summary = "获取收到的好友请求列表")
    public Result<IPage<FriendRequestDetailVO>> getReceivedRequests(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FriendRequestDetailVO> result = friendService.getReceivedRequests(userId, page);
        return Result.success(result);
    }

    @GetMapping("/requests/sent")
    @Operation(summary = "获取发送的好友请求列表")
    public Result<IPage<FriendRequestDetailVO>> getSentRequests(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FriendRequestDetailVO> result = friendService.getSentRequests(userId, page);
        return Result.success(result);
    }

    @PostMapping("/requests/{requestId}/accept")
    @Operation(summary = "接受好友请求")
    @OperationLog(module = "好友", operationType = "更新", description = "接受好友请求")
    public Result<String> acceptRequest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "请求ID") @PathVariable String requestId) {
        friendService.acceptRequest(userId, requestId);
        return Result.success();
    }

    @PostMapping("/requests/{requestId}/reject")
    @Operation(summary = "拒绝好友请求")
    @OperationLog(module = "好友", operationType = "更新", description = "拒绝好友请求")
    public Result<String> rejectRequest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "请求ID") @PathVariable String requestId) {
        friendService.rejectRequest(userId, requestId);
        return Result.success();
    }

    @DeleteMapping("/requests/{requestId}")
    @Operation(summary = "取消好友请求")
    @OperationLog(module = "好友", operationType = "删除", description = "取消好友请求")
    public Result<String> cancelRequest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "请求ID") @PathVariable String requestId) {
        friendService.cancelRequest(userId, requestId);
        return Result.success();
    }

    @GetMapping("/requests/pending-count")
    @Operation(summary = "获取待处理的好友请求数量")
    public Result<Map<String, Integer>> getPendingCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = friendService.getPendingCount(userId);
        return Result.success(Map.of("count", count));
    }

    // ==================== 好友列表相关 ====================

    @GetMapping
    @Operation(summary = "获取好友列表（分页）")
    public Result<IPage<FriendVO>> getFriends(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FriendVO> result = friendService.getFriends(userId, page);
        return Result.success(result);
    }

    @GetMapping("/all")
    @Operation(summary = "获取所有好友（用于选择器）")
    public Result<List<FriendVO>> getAllFriends(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        List<FriendVO> result = friendService.getAllFriends(userId);
        return Result.success(result);
    }

    @DeleteMapping("/{friendId}")
    @Operation(summary = "解除好友关系")
    @OperationLog(module = "好友", operationType = "删除", description = "解除好友关系")
    public Result<String> unfriend(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "好友ID") @PathVariable String friendId) {
        friendService.unfriend(userId, friendId);
        return Result.success();
    }

    @PutMapping("/{friendId}/remark")
    @Operation(summary = "更新好友备注")
    @OperationLog(module = "好友", operationType = "更新", description = "更新好友备注")
    public Result<String> updateRemark(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "好友ID") @PathVariable String friendId,
            @Valid @RequestBody UpdateRemarkVO vo) {
        friendService.updateRemark(userId, friendId, vo.getRemark());
        return Result.success();
    }

    // ==================== 用户搜索相关 ====================

    @GetMapping("/search")
    @Operation(summary = "搜索用户", description = "根据用户名或昵称搜索用户")
    public Result<List<UserSearchVO>> searchUsers(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "搜索关键词")
            @RequestParam @NotBlank(message = "搜索关键词不能为空") String keyword) {
        List<UserSearchVO> result = friendService.searchUsers(userId, keyword);
        return Result.success(result);
    }
}
