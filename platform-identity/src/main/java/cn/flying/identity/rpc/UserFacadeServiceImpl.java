package cn.flying.identity.rpc;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.identity.UserFacadeService;
import cn.flying.platformapi.identity.dto.SessionInfo;
import cn.flying.platformapi.identity.dto.UserInfo;
import cn.flying.identity.dto.UserSession;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.UserSessionService;
import cn.flying.identity.dto.Account;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import cn.dev33.satoken.stp.StpInterface;

import java.util.*;

@Slf4j
@DubboService(protocol = "tri")
public class UserFacadeServiceImpl implements UserFacadeService {

    @Resource
    private AccountMapper accountMapper;

    @Resource
    private UserSessionService userSessionService;

    @Resource
    private StpInterface stpInterface;

    @Override
    public Result<UserInfo> getUserById(Long userId) {
        try {
            Account account = accountMapper.selectById(userId);
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }
            UserInfo ui = new UserInfo();
            ui.setUserId(account.getId());
            ui.setUsername(account.getUsername());
            ui.setEmail(account.getEmail());
            ui.setAvatar(account.getAvatar());
            ui.setRole(account.getRole());
            ui.setRegisterTime(java.sql.Timestamp.valueOf(account.getRegisterTime()));
            return Result.success(ui);
        } catch (Exception e) {
            log.error("getUserById error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<List<UserInfo>> getUsersByIds(List<Long> userIds) {
        try {
            if (userIds == null || userIds.isEmpty()) {
                return Result.success(Collections.emptyList());
            }
            List<Account> accounts = accountMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<Account>lambdaQuery().in(Account::getId, userIds));
            if (accounts == null || accounts.isEmpty()) {
                return Result.success(Collections.emptyList());
            }
            List<UserInfo> list = new ArrayList<>(accounts.size());
            for (Account a : accounts) {
                UserInfo ui = new UserInfo();
                ui.setUserId(a.getId());
                ui.setUsername(a.getUsername());
                ui.setEmail(a.getEmail());
                ui.setAvatar(a.getAvatar());
                ui.setRole(a.getRole());
                ui.setRegisterTime(a.getRegisterTime() == null ? null : java.sql.Timestamp.valueOf(a.getRegisterTime()));
                list.add(ui);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("getUsersByIds error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<List<String>> getUserRoles(Long userId) {
        try {
            return Result.success(stpInterface.getRoleList(userId, null));
        } catch (Exception e) {
            log.error("getUserRoles error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<List<String>> getUserPermissions(Long userId) {
        try {
            return Result.success(stpInterface.getPermissionList(userId, null));
        } catch (Exception e) {
            log.error("getUserPermissions error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<List<SessionInfo>> listActiveSessions(Long userId) {
        try {
            var sessions = userSessionService.findActiveSessionsByUserId(userId);
            if (!sessions.isSuccess()) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
            List<SessionInfo> list = new ArrayList<>();
            for (UserSession us : sessions.getData()) {
                SessionInfo si = new SessionInfo();
                si.setSessionId(us.getSessionId());
                si.setUserId(us.getUserId());
                si.setClientIp(us.getClientIp());
                si.setUserAgent(us.getUserAgent());
                si.setLoginTime(java.sql.Timestamp.valueOf(us.getLoginTime()));
                si.setLastAccessTime(java.sql.Timestamp.valueOf(us.getLastAccessTime()));
                si.setExpireTime(us.getExpireTime() == null ? null : java.sql.Timestamp.valueOf(us.getExpireTime()));
                si.setStatus(us.getStatus());
                list.add(si);
            }
            return Result.success(list);
        } catch (Exception e) {
            log.error("listActiveSessions error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> forceLogoutSession(String sessionId) {
        try {
            return userSessionService.logoutSession(sessionId, UserSession.LogoutReason.FORCE_LOGOUT);
        } catch (Exception e) {
            log.error("forceLogoutSession error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> forceLogoutAll(Long userId) {
        try {
            return userSessionService.logoutAllUserSessions(userId, UserSession.LogoutReason.FORCE_LOGOUT);
        } catch (Exception e) {
            log.error("forceLogoutAll error", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
}
