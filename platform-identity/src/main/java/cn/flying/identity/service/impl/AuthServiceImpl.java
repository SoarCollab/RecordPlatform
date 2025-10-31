package cn.flying.identity.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.dto.Account;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.AuthService;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.identity.util.FlowUtils;
import cn.flying.identity.util.WebContextUtils;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.LoginStatusVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.crypto.SecureUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Locale;
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

    @Resource
    private FlowUtils flowUtils;

    @Resource
    private ApplicationProperties applicationProperties;

    @Override
    public String login(String username, String password) {
        String clientIp = WebContextUtils.getCurrentClientIp();
        ApplicationProperties.LoginSecurity loginSecurity = applicationProperties.getLoginSecurity();
        String accountIdentifier = buildAccountIdentifier(username);
        String ipIdentifier = buildIpIdentifier(clientIp);

        enforceLoginRateLimit(loginSecurity, username, clientIp, accountIdentifier, ipIdentifier);

        Account account = accountService.findAccountByNameOrEmail(username);
        if (account == null) {
            recordLoginFailure(loginSecurity, username, clientIp, accountIdentifier, ipIdentifier);
            logAuthenticationFailure(username, clientIp, "用户不存在");
            throw new BusinessException(ResultEnum.USER_LOGIN_ERROR, "账号或密码错误");
        }

        if (!accountService.matchesPassword(password, account.getPassword())) {
            recordLoginFailure(loginSecurity, username, clientIp, accountIdentifier, ipIdentifier);
            logAuthenticationFailure(username, clientIp, "密码错误");
            throw new BusinessException(ResultEnum.USER_LOGIN_ERROR);
        }

        clearLoginFailureCounters(loginSecurity, accountIdentifier, ipIdentifier);

        StpUtil.login(account.getId());

        SaSession session = StpUtil.getSession();
        if (session != null) {
            session.set("role", account.getRole());
            session.set("username", account.getUsername());
            session.set("email", account.getEmail());
        }

        String token = StpUtil.getTokenValue();

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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
    public void resetConfirm(EmailResetVO vo) {
        Result<Void> result = accountService.resetEmailAccountPassword(vo);
        ensureResultSuccess(result, ResultEnum.FAIL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    private void logAccessDenied(String resource, String username, String reason) {
        log.warn("Access denied - Resource: {}, User: {}, Reason: {}",
                resource, maskUsername(username), reason);
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

    private String buildAccountIdentifier(String username) {
        if (username == null || username.isBlank()) {
            return "acct:unknown";
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return "acct:" + SecureUtil.sha256(normalized);
    }

    private String buildIpIdentifier(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return null;
        }
        return "ip:" + SecureUtil.sha256(clientIp);
    }

    private void enforceLoginRateLimit(ApplicationProperties.LoginSecurity loginSecurity,
                                       String username,
                                       String clientIp,
                                       String accountIdentifier,
                                       String ipIdentifier) {
        if (loginSecurity == null) {
            return;
        }

        if (loginSecurity.getMaxAttemptsPerAccount() > 0) {
            int accountFailures = flowUtils.getLoginFailureCount(accountIdentifier);
            if (accountFailures >= loginSecurity.getMaxAttemptsPerAccount()) {
                log.warn("账号登录尝试次数过多 - User: {}, IP: {}",
                        maskUsername(username), clientIp);
                throw buildTooManyAttemptsException(loginSecurity, "该账号");
            }
        }

        if (ipIdentifier != null && loginSecurity.getMaxAttemptsPerIp() > 0) {
            int ipFailures = flowUtils.getLoginFailureCount(ipIdentifier);
            if (ipFailures >= loginSecurity.getMaxAttemptsPerIp()) {
                log.warn("IP 登录尝试次数过多 - IP: {}", clientIp);
                throw buildTooManyAttemptsException(loginSecurity, "当前IP");
            }
        }
    }

    private void recordLoginFailure(ApplicationProperties.LoginSecurity loginSecurity,
                                    String username,
                                    String clientIp,
                                    String accountIdentifier,
                                    String ipIdentifier) {
        if (loginSecurity == null) {
            return;
        }

        int windowSeconds = Math.max(60, loginSecurity.getWindowSeconds());
        int accountFailures = flowUtils.recordLoginFailure(accountIdentifier, windowSeconds);
        if (loginSecurity.getMaxAttemptsPerAccount() > 0
                && accountFailures >= loginSecurity.getMaxAttemptsPerAccount()) {
            log.warn("账号登录失败次数达到阈值 - User: {}, IP: {}, Failures: {}",
                    maskUsername(username), clientIp, accountFailures);
        }

        if (ipIdentifier != null && loginSecurity.getMaxAttemptsPerIp() > 0) {
            int ipFailures = flowUtils.recordLoginFailure(ipIdentifier, windowSeconds);
            if (ipFailures >= loginSecurity.getMaxAttemptsPerIp()) {
                log.warn("IP 登录失败次数达到阈值 - IP: {}, Failures: {}",
                        clientIp, ipFailures);
            }
        }
    }

    private void logAuthenticationFailure(String username, String ipAddress, String reason) {
        log.warn("Authentication failed - User: {}, IP: {}, Reason: {}",
                maskUsername(username), ipAddress, reason);
    }

    private void clearLoginFailureCounters(ApplicationProperties.LoginSecurity loginSecurity,
                                           String accountIdentifier,
                                           String ipIdentifier) {
        if (loginSecurity == null) {
            return;
        }

        try {
            flowUtils.clearLoginFailure(accountIdentifier);
        } catch (Exception e) {
            log.debug("清除账号登录失败计数失败: {}", e.getMessage());
        }

        if (ipIdentifier != null) {
            try {
                flowUtils.clearLoginFailure(ipIdentifier);
            } catch (Exception e) {
                log.debug("清除IP登录失败计数失败: {}", e.getMessage());
            }
        }
    }

    private void logAuthenticationSuccess(String username, String ipAddress) {
        log.info("Authentication successful - User: {}, IP: {}",
                maskUsername(username), ipAddress);
    }

    private String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "***";
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.charAt(0) + "***" + username.substring(username.length() - 1);
    }

    private BusinessException buildTooManyAttemptsException(ApplicationProperties.LoginSecurity loginSecurity,
                                                            String scope) {
        int lockMinutes = Math.max(1, loginSecurity.getLockMinutes());
        String message = scope + "登录尝试次数过多，请在" + lockMinutes + "分钟后重试";
        return new BusinessException(ResultEnum.PERMISSION_LIMIT.getCode(), message);
    }
}
