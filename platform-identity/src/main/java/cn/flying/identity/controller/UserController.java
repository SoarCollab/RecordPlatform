package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.ModifyEmailVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理控制器
 * 提供用户信息管理、密码修改、邮箱修改等功能
 * 
 * @author 王贝强
 */
@RestController
@RequestMapping("/api/user")
@Tag(name = "用户管理", description = "用户信息管理、密码修改、邮箱修改等功能")
@SaCheckLogin
public class UserController {

    @Autowired
    private AccountService accountService;

    /**
     * 获取当前用户信息
     * 
     * @return 用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的详细信息")
    public Result<AccountVO> getUserInfo() {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Account account = accountService.findAccountById(userId);
            
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }
            
            AccountVO vo = new AccountVO();
            BeanUtils.copyProperties(account, vo);
            vo.setExternalId(String.valueOf(account.getId()));
            
            return Result.success(vo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 修改用户密码
     * 
     * @param vo 修改密码信息
     * @return 修改结果
     */
    @PostMapping("/change-password")
    @Operation(summary = "修改用户密码", description = "修改当前用户的登录密码")
    public Result<Void> changePassword(@RequestBody @Valid ChangePasswordVO vo) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            return accountService.changePassword(userId, vo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 修改用户邮箱
     * 
     * @param vo 修改邮箱信息
     * @return 修改结果
     */
    @PostMapping("/modify-email")
    @Operation(summary = "修改用户邮箱", description = "修改当前用户的邮箱地址")
    public Result<Void> modifyEmail(@RequestBody @Valid ModifyEmailVO vo) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            return accountService.modifyEmail(userId, vo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取用户基本信息（公开信息）
     * 
     * @param userId 用户ID
     * @return 用户基本信息
     */
    @GetMapping("/{userId}/profile")
    @Operation(summary = "获取用户基本信息", description = "获取指定用户的公开信息")
    public Result<Map<String, Object>> getUserProfile(
            @Parameter(description = "用户ID") @PathVariable Long userId) {
        
        try {
            Account account = accountService.findAccountById(userId);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }
            
            // 只返回公开信息
            Map<String, Object> profile = Map.of(
                "id", account.getId(),
                "username", account.getUsername(),
                "avatar", account.getAvatar() != null ? account.getAvatar() : "",
                "role", account.getRole(),
                "registerTime", account.getRegisterTime()
            );
            
            return Result.success(profile);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 更新用户头像
     * 
     * @param avatarUrl 头像URL
     * @return 更新结果
     */
    @PostMapping("/avatar")
    @Operation(summary = "更新用户头像", description = "更新当前用户的头像")
    public Result<Void> updateAvatar(@RequestParam String avatarUrl) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("avatar", avatarUrl)
                    .update();
            
            return success ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 注销当前用户账号
     * 
     * @param password 确认密码
     * @return 注销结果
     */
    @PostMapping("/deactivate")
    @Operation(summary = "注销用户账号", description = "注销当前用户账号（软删除）")
    public Result<Void> deactivateAccount(@RequestParam String password) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Account account = accountService.findAccountById(userId);
            
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }
            
            // 验证密码
            if (!accountService.matchesPassword(password, account.getPassword())) {
                return Result.error(ResultEnum.USER_LOGIN_ERROR, null);
            }
            
            // 软删除用户
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("deleted", 1)
                    .update();
            
            if (success) {
                // 注销登录
                StpUtil.logout();
                return Result.success(null);
            } else {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    // 管理员接口

    /**
     * 获取用户列表（管理员）
     * 
     * @param page 页码
     * @param size 页大小
     * @param keyword 搜索关键词
     * @return 用户列表
     */
    @GetMapping("/admin/list")
    @Operation(summary = "获取用户列表", description = "管理员获取用户列表")
    @SaCheckRole("admin")
    public Result<Map<String, Object>> getUserList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword) {
        
        try {
            // 构建查询条件
            var query = accountService.query().eq("deleted", 0);
            
            if (keyword != null && !keyword.trim().isEmpty()) {
                query.and(wrapper -> wrapper
                        .like("username", keyword)
                        .or()
                        .like("email", keyword));
            }
            
            // 分页查询
            var pageResult = query.page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, size));
            
            // 转换为VO
            List<AccountVO> users = pageResult.getRecords().stream()
                    .map(account -> {
                        AccountVO vo = new AccountVO();
                        BeanUtils.copyProperties(account, vo);
                        vo.setExternalId(String.valueOf(account.getId()));
                        return vo;
                    })
                    .collect(Collectors.toList());
            
            Map<String, Object> result = Map.of(
                "users", users,
                "total", pageResult.getTotal(),
                "page", page,
                "size", size,
                "pages", pageResult.getPages()
            );
            
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 禁用/启用用户（管理员）
     * 
     * @param userId 用户ID
     * @param disabled 是否禁用
     * @return 操作结果
     */
    @PostMapping("/admin/{userId}/status")
    @Operation(summary = "禁用/启用用户", description = "管理员禁用或启用指定用户")
    @SaCheckRole("admin")
    public Result<Void> updateUserStatus(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "是否禁用") @RequestParam boolean disabled) {
        
        try {
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("deleted", disabled ? 1 : 0)
                    .update();
            
            return success ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 重置用户密码（管理员）
     * 
     * @param userId 用户ID
     * @param newPassword 新密码
     * @return 重置结果
     */
    @PostMapping("/admin/{userId}/reset-password")
    @Operation(summary = "重置用户密码", description = "管理员重置指定用户的密码")
    @SaCheckRole("admin")
    public Result<Void> resetUserPassword(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @Parameter(description = "新密码") @RequestParam String newPassword) {
        
        try {
            String encodedPassword = accountService.encodePassword(newPassword);
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("password", encodedPassword)
                    .update();
            
            return success ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
