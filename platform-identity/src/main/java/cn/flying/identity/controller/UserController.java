package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.*;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.RestResponse;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.ModifyEmailVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户管理控制器
 * 提供符合RESTful规范的用户信息管理API
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户信息管理、密码修改、邮箱修改等功能")
@SaCheckLogin
public class UserController {

    @Resource
    private AccountService accountService;

    /**
     * 获取当前用户信息
     * GET /api/users/me - 获取当前登录用户的详细信息
     */
    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<AccountVO>> getCurrentUser() {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Account account = accountService.findAccountById(userId);

            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RestResponse.notFound(ResultEnum.USER_NOT_EXIST.getCode(),
                                "用户不存在"));
            }

            AccountVO vo = new AccountVO();
            BeanUtils.copyProperties(account, vo);
            vo.setExternalId(String.valueOf(account.getId()));

            return ResponseEntity.ok(RestResponse.ok(vo));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 更新当前用户信息
     * PATCH /api/users/me - 部分更新当前用户信息
     */
    @PatchMapping("/me")
    @Operation(summary = "更新用户信息", description = "部分更新当前用户信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<AccountVO>> updateCurrentUser(@RequestBody UserUpdateRequest request) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();

            // 构建更新
            var update = accountService.update().eq("id", userId);

            if (request.getAvatar() != null) {
                update.set("avatar", request.getAvatar());
            }

            boolean success = update.update();

            if (success) {
                Account account = accountService.findAccountById(userId);
                AccountVO vo = new AccountVO();
                BeanUtils.copyProperties(account, vo);
                vo.setExternalId(String.valueOf(account.getId()));

                return ResponseEntity.ok(RestResponse.ok("用户信息更新成功", vo));
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                                "更新失败"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 修改当前用户密码
     * PUT /api/users/me/password - 修改当前用户密码
     */
    @PutMapping("/me/password")
    @Operation(summary = "修改用户密码", description = "修改当前用户的登录密码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "密码修改成功"),
            @ApiResponse(responseCode = "400", description = "原密码错误或新密码格式不正确"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<Void>> changePassword(@RequestBody @Valid ChangePasswordVO vo) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Result<Void> result = accountService.changePassword(userId, vo);
            RestResponse<Void> response = ResponseConverter.convert(result);

            return ResponseEntity.status(response.getStatus()).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 修改当前用户邮箱
     * PUT /api/users/me/email - 修改当前用户邮箱
     */
    @PutMapping("/me/email")
    @Operation(summary = "修改用户邮箱", description = "修改当前用户的邮箱地址")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "邮箱修改成功"),
            @ApiResponse(responseCode = "400", description = "验证码错误或邮箱格式不正确"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "409", description = "邮箱已被使用")
    })
    public ResponseEntity<RestResponse<Void>> modifyEmail(@RequestBody @Valid ModifyEmailVO vo) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Result<Void> result = accountService.modifyEmail(userId, vo);
            RestResponse<Void> response = ResponseConverter.convert(result);

            return ResponseEntity.status(response.getStatus()).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 获取指定用户公开信息
     * GET /api/users/{userId} - 获取指定用户的公开信息
     */
    @GetMapping("/{userId}")
    @Operation(summary = "获取用户公开信息", description = "获取指定用户的公开信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getUserProfile(
            @Parameter(description = "用户ID") @PathVariable Long userId) {

        try {
            Account account = accountService.findAccountById(userId);
            if (account == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RestResponse.notFound(ResultEnum.USER_NOT_EXIST.getCode(),
                                "用户不存在"));
            }

            // 只返回公开信息
            Map<String, Object> profile = Map.of(
                    "id", account.getId(),
                    "username", account.getUsername(),
                    "avatar", account.getAvatar() != null ? account.getAvatar() : "",
                    "role", account.getRole(),
                    "registerTime", account.getRegisterTime()
            );

            return ResponseEntity.ok(RestResponse.ok(profile));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 删除当前用户账号
     * DELETE /api/users/me - 注销当前用户账号（软删除）
     */
    @DeleteMapping("/me")
    @Operation(summary = "注销用户账号", description = "注销当前用户账号（需要密码确认）")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功"),
            @ApiResponse(responseCode = "400", description = "密码错误"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    public ResponseEntity<Void> deactivateAccount(@RequestBody DeactivateRequest request) {
        try {
            Long userId = StpUtil.getLoginIdAsLong();
            Account account = accountService.findAccountById(userId);

            if (account == null) {
                return ResponseEntity.notFound().build();
            }

            // 验证密码
            if (!accountService.matchesPassword(request.getPassword(), account.getPassword())) {
                return ResponseEntity.badRequest().build();
            }

            // 软删除用户
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("deleted", 1)
                    .update();

            if (success) {
                // 注销登录
                StpUtil.logout();
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== 管理员接口 ====================

    /**
     * 获取用户列表
     * GET /api/users - 获取用户列表（管理员）
     */
    @GetMapping
    @Operation(summary = "获取用户列表", description = "管理员获取用户列表，支持分页和搜索")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckRole("admin")
    public ResponseEntity<RestResponse<UserListResponse>> getUserList(
            @Parameter(description = "页码，从0开始") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "页大小") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String q) {

        try {
            // 构建查询条件
            var query = accountService.query().eq("deleted", 0);

            if (q != null && !q.trim().isEmpty()) {
                query.and(wrapper -> wrapper
                        .like("username", q)
                        .or()
                        .like("email", q));
            }

            // 分页查询
            var pageResult = query.page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page + 1, size));

            // 转换为VO
            List<AccountVO> users = pageResult.getRecords().stream()
                    .map(acc -> {
                        AccountVO vo = new AccountVO();
                        BeanUtils.copyProperties(acc, vo);
                        vo.setExternalId(String.valueOf(acc.getId()));
                        return vo;
                    })
                    .collect(Collectors.toList());

            UserListResponse response = new UserListResponse();
            response.setContent(users);
            response.setTotalElements(pageResult.getTotal());
            response.setTotalPages(pageResult.getPages());
            response.setNumber(page);
            response.setSize(size);
            response.setFirst(page == 0);
            response.setLast(page == pageResult.getPages() - 1);

            return ResponseEntity.ok(RestResponse.ok(response));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 更新用户状态
     * PATCH /api/users/{userId}/status - 启用/禁用用户（管理员）
     */
    @PatchMapping("/{userId}/status")
    @Operation(summary = "更新用户状态", description = "管理员启用或禁用指定用户")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @SaCheckRole("admin")
    public ResponseEntity<RestResponse<Void>> updateUserStatus(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @RequestBody UserStatusRequest request) {

        try {
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("deleted", request.isDisabled() ? 1 : 0)
                    .update();

            if (success) {
                return ResponseEntity.ok(RestResponse.ok("用户状态更新成功", null));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RestResponse.notFound(ResultEnum.USER_NOT_EXIST.getCode(),
                                "用户不存在"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }

    /**
     * 重置用户密码
     * PUT /api/users/{userId}/password - 重置用户密码（管理员）
     */
    @PutMapping("/{userId}/password")
    @Operation(summary = "重置用户密码", description = "管理员重置指定用户的密码")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "重置成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "用户不存在")
    })
    @SaCheckRole("admin")
    public ResponseEntity<RestResponse<Void>> resetUserPassword(
            @Parameter(description = "用户ID") @PathVariable Long userId,
            @RequestBody PasswordResetRequest request) {

        try {
            String encodedPassword = accountService.encodePassword(request.getNewPassword());
            boolean success = accountService.update()
                    .eq("id", userId)
                    .set("password", encodedPassword)
                    .update();

            if (success) {
                return ResponseEntity.ok(RestResponse.ok("密码重置成功", null));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(RestResponse.notFound(ResultEnum.USER_NOT_EXIST.getCode(),
                                "用户不存在"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(),
                            "系统内部错误"));
        }
    }
}
