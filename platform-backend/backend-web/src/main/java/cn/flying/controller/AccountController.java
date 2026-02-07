package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.ControllerUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.AccountVO;
import cn.flying.dao.vo.auth.ChangePasswordVO;
import cn.flying.dao.vo.auth.ModifyEmailVO;
import cn.flying.dao.vo.auth.UpdateUserVO;
import cn.flying.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @program: forum
 * @description: 用户信息相关接口
 * @author flyingcoding
 * @create: 2024-06-07 20:55
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "用户信息、隐私设置相关", description = "包括用户信息、详细信息、隐私设置等操作。")
public class AccountController {
    @Resource
    AccountService accountService;
    @Resource
    ControllerUtils utils;

    /**
     * 获取用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取用户信息")
    @OperationLog(module = "用户模块", operationType = "查询", description = "获取用户信息")
    public Result<AccountVO> getAccountInfo(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        Account account = accountService.findAccountById(userId);
        return Result.success(account.asViewObject(AccountVO.class));
    }

    /**
     * 更新用户信息。
     *
     * @param userId 用户 ID
     * @param vo     更新请求
     * @return 更新后的用户信息
     */
    @PutMapping("/info")
    @Operation(summary = "更新用户信息")
    @OperationLog(module = "用户模块", operationType = "修改", description = "更新用户信息")
    public Result<AccountVO> updateUserInfo(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestBody @Valid UpdateUserVO vo) {
        Account account = accountService.updateUserInfo(userId, vo);
        return Result.success(account.asViewObject(AccountVO.class));
    }

    /**
     * 修改邮箱地址（REST 新路径）。
     *
     * @param userId        用户 ID
     * @param modifyEmailVO 修改邮箱参数
     * @return 操作结果
     */
    @PutMapping("/email")
    @Operation(summary = "修改邮箱地址（REST）")
    @OperationLog(module = "用户模块", operationType = "修改", description = "修改邮箱地址（REST）")
    public Result<String> updateEmail(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @RequestBody @Valid ModifyEmailVO modifyEmailVO) {
        return utils.messageHandle(() -> accountService.modifyEmail(userId, modifyEmailVO));
    }

    /**
     * 修改密码（REST 新路径）。
     *
     * @param userId           用户 ID
     * @param changePasswordVO 修改密码参数
     * @return 操作结果
     */
    @PutMapping("/password")
    @Operation(summary = "修改密码（REST）")
    @OperationLog(module = "用户模块", operationType = "修改", description = "修改密码（REST）")
    public Result<String> updatePassword(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                         @RequestBody @Valid ChangePasswordVO changePasswordVO) {
        return utils.messageHandle(() -> accountService.changePassword(userId, changePasswordVO));
    }
}
