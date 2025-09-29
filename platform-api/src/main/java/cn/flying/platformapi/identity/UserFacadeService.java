package cn.flying.platformapi.identity;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.identity.dto.SessionInfo;
import cn.flying.platformapi.identity.dto.UserInfo;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 用户与会话对内 Dubbo 门面接口
 */
@DubboService
public interface UserFacadeService {

    Result<UserInfo> getUserById(Long userId);

    Result<List<UserInfo>> getUsersByIds(java.util.List<Long> userIds);

    Result<java.util.List<String>> getUserRoles(Long userId);

    Result<java.util.List<String>> getUserPermissions(Long userId);

    Result<java.util.List<SessionInfo>> listActiveSessions(Long userId);

    Result<Void> forceLogoutSession(String sessionId);

    Result<Void> forceLogoutAll(Long userId);
}
