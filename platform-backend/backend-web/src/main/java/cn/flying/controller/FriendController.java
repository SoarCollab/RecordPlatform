package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.FriendRequest;
import cn.flying.dao.vo.friend.FriendRequestDetailVO;
import cn.flying.dao.vo.friend.FriendVO;
import cn.flying.dao.vo.friend.SendFriendRequestVO;
import cn.flying.dao.vo.friend.UpdateRemarkVO;
import cn.flying.dao.vo.friend.UserSearchVO;
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

import java.util.List;
import java.util.Map;

/**
 * 好友管理控制器。
 */
@RestController
@RequestMapping("/api/v1/friends")
@Tag(name = "好友管理", description = "好友请求、好友列表、用户搜索等操作")
@Validated
public class FriendController {

    @Resource
    private FriendService friendService;

    /**
     * 发送好友请求。
     *
     * @param userId 当前用户 ID
     * @param vo     请求体
     * @return 好友请求详情
     */
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

    /**
     * 获取收到的好友请求列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
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

    /**
     * 获取发送的好友请求列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
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

    /**
     * 更新好友请求状态（REST 新路径）。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求外部 ID
     * @param status    状态值（accept/reject/accepted/rejected）
     * @return 操作结果
     */
    @PutMapping("/requests/{requestId}/status")
    @Operation(summary = "更新好友请求状态（REST）")
    @OperationLog(module = "好友", operationType = "更新", description = "更新好友请求状态（REST）")
    public Result<String> updateRequestStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "请求ID") @PathVariable String requestId,
            @Parameter(description = "状态：accept/reject/accepted/rejected") @RequestParam("status") String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        if ("accept".equals(normalized) || "accepted".equals(normalized)) {
            friendService.acceptRequest(userId, requestId);
            return Result.success();
        }
        if ("reject".equals(normalized) || "rejected".equals(normalized)) {
            friendService.rejectRequest(userId, requestId);
            return Result.success();
        }
        throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "status 仅支持 accept/reject");
    }

    /**
     * 取消好友请求。
     *
     * @param userId    当前用户 ID
     * @param requestId 请求外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/requests/{requestId}")
    @Operation(summary = "取消好友请求")
    @OperationLog(module = "好友", operationType = "删除", description = "取消好友请求")
    public Result<String> cancelRequest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "请求ID") @PathVariable String requestId) {
        friendService.cancelRequest(userId, requestId);
        return Result.success();
    }

    /**
     * 获取待处理好友请求数量。
     *
     * @param userId 当前用户 ID
     * @return 待处理数量
     */
    @GetMapping("/requests/pending-count")
    @Operation(summary = "获取待处理的好友请求数量")
    public Result<Map<String, Integer>> getPendingCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = friendService.getPendingCount(userId);
        return Result.success(Map.of("count", count));
    }

    /**
     * 获取好友列表（分页）。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
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

    /**
     * 获取所有好友（用于选择器）。
     *
     * @param userId 当前用户 ID
     * @return 好友列表
     */
    @GetMapping("/all")
    @Operation(summary = "获取所有好友（用于选择器）")
    public Result<List<FriendVO>> getAllFriends(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        List<FriendVO> result = friendService.getAllFriends(userId);
        return Result.success(result);
    }

    /**
     * 解除好友关系。
     *
     * @param userId   当前用户 ID
     * @param friendId 好友外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/{friendId}")
    @Operation(summary = "解除好友关系")
    @OperationLog(module = "好友", operationType = "删除", description = "解除好友关系")
    public Result<String> unfriend(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "好友ID") @PathVariable String friendId) {
        friendService.unfriend(userId, friendId);
        return Result.success();
    }

    /**
     * 更新好友备注。
     *
     * @param userId   当前用户 ID
     * @param friendId 好友外部 ID
     * @param vo       备注内容
     * @return 操作结果
     */
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

    /**
     * 搜索用户。
     *
     * @param userId  当前用户 ID
     * @param keyword 关键词
     * @return 用户列表
     */
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
