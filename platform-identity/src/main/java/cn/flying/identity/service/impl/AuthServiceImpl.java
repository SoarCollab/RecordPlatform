package cn.flying.identity.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.identity.util.WebContextUtils;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.LoginStatusVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务实现类
 * 基于Sa-Token实现用户认证、注册、密码管理等功能
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private AccountService accountService;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    @Override
    public String login(String username, String password) {
        Account account = accountService.findAccountByNameOrEmail(username);
        if (account == null) {
            String clientIp = WebContextUtils.getCurrentClientIp();
            logAuthenticationFailure(username, clientIp, "用户不存在");
            throw new BusinessException(ResultEnum.USER_NOT_EXIST);
        }

        if (!accountService.matchesPassword(password, account.getPassword())) {
            String clientIp = WebContextUtils.getCurrentClientIp();
            logAuthenticationFailure(username, clientIp, "密码错误");
            throw new BusinessException(ResultEnum.USER_LOGIN_ERROR);
        }

        StpUtil.login(account.getId());

        SaSession session = StpUtil.getSession();
        if (session != null) {
            session.set("role", account.getRole());
            session.set("username", account.getUsername());
            session.set("email", account.getEmail());
        }

        String token = StpUtil.getTokenValue();

        String clientIp = WebContextUtils.getCurrentClientIp();
        logAuthenticationSuccess(username, clientIp);

        return token;
    }

    @Override
    public void logout() {
        String username = "unknown";

        if (StpUtil.isLogin()) {
            try {
                SaSession session = StpUtil.getSession();
                if (session != null) {
                    Object usernameAttr = session.get("username");
                    if (usernameAttr != null) {
                        username = String.valueOf(usernameAttr);
                    }
                }
            } catch (Exception e) {
                log.debug("获取会话用户名失败，使用默认用户名: {}", e.getMessage());
            }

            try {
                String token = StpUtil.getTokenValue();
                if (token != null && !token.isBlank()) {
                    jwtBlacklistService.blacklistToken(token, -1L);
                }
            } catch (Exception e) {
                log.debug("添加Token到黑名单失败: {}", e.getMessage());
            }

            StpUtil.logout();
        }

        String clientIp = WebContextUtils.getCurrentClientIp();
        log.info("用户注销成功 - User: {}, IP: {}", maskUsername(username), clientIp);
    }

    @Override
    public void register(EmailRegisterVO vo) {
        Result<Void> result = accountService.registerEmailAccount(vo);
        ensureResultSuccess(result, ResultEnum.FAIL);
    }

    @Override
    public void askVerifyCode(String email, String type) {
        String clientIp = WebContextUtils.getCurrentClientIp();
        Result<Void> result = accountService.registerEmailVerifyCode(type, email, clientIp);
        ensureResultSuccess(result, ResultEnum.AUTH_CODE_ERROR);
    }

    @Override
    public void resetConfirm(EmailResetVO vo) {
        Result<Void> result = accountService.resetEmailAccountPassword(vo);
        ensureResultSuccess(result, ResultEnum.FAIL);
    }

    @Override
    public void changePassword(ChangePasswordVO vo) {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN);
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Result<Void> result = accountService.changePassword(userId, vo);
        ensureResultSuccess(result, ResultEnum.USER_PASSWORD_VERIFY_ERROR);
    }

    @Override
    public AccountVO getUserInfo() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN);
        }

        Long userId = StpUtil.getLoginIdAsLong();
        Account account = accountService.findAccountById(userId);
        if (account == null) {
            throw new BusinessException(ResultEnum.USER_NOT_EXIST);
        }

        AccountVO vo = new AccountVO();
        BeanUtils.copyProperties(account, vo);
        vo.setExternalId(String.valueOf(account.getId()));
        return vo;
    }

    @Override
    public Account findAccountByNameOrEmail(String text) {
        return accountService.findAccountByNameOrEmail(text);
    }

    @Override
    public AccountVO findUserWithMasking(String text) {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN);
        }

        SaSession session = StpUtil.getSession();
        String role = session != null ? (String) session.get("role") : null;
        String currentUser = session != null ? (String) session.get("username") : null;
        if (!"admin".equalsIgnoreCase(role)) {
            String clientIp = WebContextUtils.getCurrentClientIp();
            logAccessDenied("findUserWithMasking", currentUser, "非管理员权限");
            throw new BusinessException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        Account account = accountService.findAccountByNameOrEmail(text);
        if (account == null) {
            throw new BusinessException(ResultEnum.USER_NOT_EXIST);
        }

        AccountVO vo = new AccountVO();
        BeanUtils.copyProperties(account, vo);
        vo.setExternalId(String.valueOf(account.getId()));

        maskEmail(vo, account.getEmail());
        return vo;
    }

    @Override
    public LoginStatusVO checkLoginStatus() {
        boolean isLogin = StpUtil.isLogin();
        Long userId = null;
        String message;
        if (isLogin) {
            userId = StpUtil.getLoginIdAsLong();
            message = "用户已登录";
        } else {
            message = "用户未登录";
        }
        return new LoginStatusVO(isLogin, message, userId);
    }

    @Override
    public Map<String, Object> getTokenInfo() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN);
        }

        SaTokenInfo info = StpUtil.getTokenInfo();
        Map<String, Object> tokenInfo = new HashMap<>();
        if (info != null) {
            tokenInfo.put("tokenName", info.getTokenName());
            tokenInfo.put("tokenValue", info.getTokenValue());
            tokenInfo.put("loginId", info.getLoginId());
            tokenInfo.put("loginType", info.getLoginType());
            tokenInfo.put("isLogin", info.getIsLogin());
            tokenInfo.put("tokenTimeout", info.getTokenTimeout());
            tokenInfo.put("sessionTimeout", info.getSessionTimeout());
            tokenInfo.put("tokenSessionTimeout", info.getTokenSessionTimeout());
        }
        return tokenInfo;
    }

    private void logAuthenticationFailure(String username, String ipAddress, String reason) {
        log.warn("Authentication failed - User: {}, IP: {}, Reason: {}",
                maskUsername(username), ipAddress, reason);
    }

    private void logAuthenticationSuccess(String username, String ipAddress) {
        log.info("Authentication successful - User: {}, IP: {}",
                maskUsername(username), ipAddress);
    }

    private void logAccessDenied(String resource, String username, String reason) {
        log.warn("Access denied - Resource: {}, User: {}, Reason: {}",
                resource, maskUsername(username), reason);
    }

    private String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "***";
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.substring(0, 1) + "***" + username.substring(username.length() - 1);
    }

    private void ensureResultSuccess(Result<?> result, ResultEnum fallbackEnum) {
        if (result == null) {
            throw new BusinessException(fallbackEnum);
        }
        if (!result.isSuccess()) {
            int code = result.getCode() != null ? result.getCode() : fallbackEnum.getCode();
            String message = result.getMessage() != null && !result.getMessage().isBlank()
                    ? result.getMessage()
                    : fallbackEnum.getMessage();
            throw new BusinessException(code, message);
        }
    }

    private void maskEmail(AccountVO target, String email) {
        if (email == null || email.isEmpty()) {
            return;
        }
        int atIndex = email.indexOf("@");
        if (atIndex > 3) {
            String prefix = email.substring(0, 3);
            String domain = email.substring(atIndex);
            target.setEmail(prefix + "***" + domain);
        } else if (atIndex > 0) {
            String domain = email.substring(atIndex);
            target.setEmail("***" + domain);
        }
    }
}
