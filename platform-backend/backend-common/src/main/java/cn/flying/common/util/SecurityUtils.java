package cn.flying.common.util;

import cn.flying.common.constant.UserRole;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * @program: RecordPlatform
 * @description: 系统安全相关工具类
 * @author: flyingcoding
 * @create: 2025-05-06 20:51
 */
@Component
public class SecurityUtils {

    public static UserRole getLoginUserRole(){
        String userRole = MDC.get(Const.ATTR_USER_ROLE);
        return UserRole.getRole(userRole);
    }

    public static boolean isAdmin(){
        UserRole userRole = getLoginUserRole();
        return UserRole.ROLE_ADMINISTER.equals(userRole);
    }
}
