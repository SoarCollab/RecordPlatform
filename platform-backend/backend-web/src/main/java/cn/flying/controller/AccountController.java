package cn.flying.controller;

import cn.flying.common.annotation.SecureId;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.ControllerUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.request.ChangePasswordVO;
import cn.flying.dao.vo.request.ModifyEmailVO;
import cn.flying.dao.vo.response.AccountVO;
import cn.flying.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * @program: forum
 * @description: 用户信息相关接口
 * @author: flyingcoding
 * @create: 2024-06-07 20:55
 */
@RestController
@RequestMapping("/api/user")
@Tag(name = "用户信息、隐私设置相关", description = "包括用户信息、详细信息、隐私设置等操作。")
public class AccountController {
    @Resource
    AccountService accountService;
    @Resource
    ControllerUtils utils;

    /**
     * 获取用户信息
     * @param userId 用户ID
     * @return 用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取用户信息")
    @SecureId(hideOriginalId = true)
    public Result<AccountVO> getAccountInfo(@RequestAttribute(Const.ATTR_USER_ID) String userId) {
        Account account = accountService.findAccountById(IdUtils.fromExternalId(userId));
        return Result.success(account.asViewObject(AccountVO.class));
    }
    /**
     * 修改邮箱地址
     * @param userId 用户ID
     * @param modifyEmailVO 修改邮箱信息
     * @return 是否修改成功
     */
    @PostMapping("/modify-email")
    @Operation(summary = "修改邮箱地址")
    public Result<String> modifyEmail(@RequestAttribute(Const.ATTR_USER_ID) Long userId, @RequestBody @Valid ModifyEmailVO modifyEmailVO) {
        return utils.messageHandle(() ->
                accountService.modifyEmail(userId, modifyEmailVO));
    }
    /**
     * 修改密码
     * @param userId 用户ID
     * @param changePasswordVO 修改密码信息
     * @return 是否修改成功
     */
    @PostMapping("/change-password")
    @Operation(summary = "修改密码")
    public Result<String> changePassword(@RequestAttribute(Const.ATTR_USER_ID) String userId, @RequestBody @Valid ChangePasswordVO changePasswordVO) {
        return utils.messageHandle(() ->
                accountService.changePassword(userId, changePasswordVO));
    }
}
