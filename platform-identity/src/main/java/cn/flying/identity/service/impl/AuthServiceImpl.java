package cn.flying.identity.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.util.IpUtils;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 认证服务实现类
 * 基于Sa-Token实现用户认证、注册、密码管理等功能
 */
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private AccountService accountService;

    // 密码相关方法委托给 AccountService

    /**
     * 用户登录
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录结果，包含Token信息
     */
    @Override
    public Result<String> login(String username, String password) {
        try {
            // 查找用户
            Account account = accountService.findAccountByNameOrEmail(username);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            // 验证密码
            if (!accountService.matchesPassword(password, account.getPassword())) {
                return Result.error(ResultEnum.USER_LOGIN_ERROR, null);
            }

            // Sa-Token登录
            StpUtil.login(account.getId());

            // 在 Session 中存储用户角色信息
            StpUtil.getSession().set("role", account.getRole());
            StpUtil.getSession().set("username", account.getUsername());
            StpUtil.getSession().set("email", account.getEmail());

            String token = StpUtil.getTokenValue();

            return Result.success(token);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 用户注销
     * @return 注销结果
     */
    @Override
    public Result<Void> logout() {
        try {
            StpUtil.logout();
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 用户注册
     * @param vo 注册信息
     * @return 注册结果
     */
    @Override
    public Result<Void> register(EmailRegisterVO vo) {
        // 直接委托给 AccountService
        return accountService.registerEmailAccount(vo);
    }

    /**
     * 发送邮箱验证码
     * @param email 邮箱地址
     * @param type 验证码类型（register/reset）
     * @return 发送结果
     */
    @Override
    public Result<Void> askVerifyCode(String email, String type) {
        // 获取客户端IP地址
        String clientIp = getCurrentRequestIp();

        // 委托给 AccountService
        return accountService.registerEmailVerifyCode(type, email, clientIp);
    }

    /**
     * 获取当前请求的IP地址
     */
    private String getCurrentRequestIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return IpUtils.getClientIp(request);
            }
        } catch (Exception e) {
            // 忽略异常，返回默认值
        }
        return "127.0.0.1";
    }

    /**
     * 重置密码确认
     * @param vo 重置密码信息
     * @return 重置结果
     */
    @Override
    public Result<Void> resetConfirm(EmailResetVO vo) {
        // 委托给 AccountService
        return accountService.resetEmailAccountPassword(vo);
    }

    /**
     * 修改密码
     * @param vo 修改密码信息
     * @return 修改结果
     */
    @Override
    public Result<Void> changePassword(ChangePasswordVO vo) {
        try {
            // 检查登录状态
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            // 获取当前用户ID
            Long userId = StpUtil.getLoginIdAsLong();

            // 委托给 AccountService
            return accountService.changePassword(userId, vo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取当前登录用户信息
     * @return 用户信息
     */
    @Override
    public Result<AccountVO> getUserInfo() {
        try {
            // 检查登录状态
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            // 获取当前用户
            Long userId = StpUtil.getLoginIdAsLong();
            Account account = accountService.findAccountById(userId);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            // 转换为VO
            AccountVO vo = new AccountVO();
            BeanUtils.copyProperties(account, vo);
            vo.setExternalId(String.valueOf(account.getId()));

            return Result.success(vo);
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 根据用户名或邮箱查找用户
     * @param text 用户名或邮箱
     * @return 用户实体
     */
    @Override
    public Account findAccountByNameOrEmail(String text) {
        return accountService.findAccountByNameOrEmail(text);
    }

    /**
     * 检查登录状态
     * @return 登录状态信息
     */
    @Override
    public Result<Object> checkLoginStatus() {
        try {
            boolean isLogin = StpUtil.isLogin();
            if (isLogin) {
                return Result.success("用户已登录，用户ID：" + StpUtil.getLoginId());
            } else {
                return Result.success("用户未登录");
            }
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    /**
     * 获取Token信息
     * @return Token详细信息
     */
    @Override
    public Result<Object> getTokenInfo() {
        try {
            if (!StpUtil.isLogin()) {
                return Result.error(ResultEnum.USER_NOT_LOGGED_IN, null);
            }

            return Result.success(StpUtil.getTokenInfo());
        } catch (Exception e) {
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}