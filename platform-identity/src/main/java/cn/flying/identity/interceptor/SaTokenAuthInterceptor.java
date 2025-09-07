package cn.flying.identity.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.constant.Const;
import cn.flying.identity.util.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * SA-Token 认证拦截器
 * 替代原来的 JWT 过滤器，使用 SA-Token 进行权限验证
 * 
 * @author 王贝强
 */
@Slf4j
@Component
public class SaTokenAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        
        log.debug("SA-Token 认证拦截器处理请求: {} {}", method, requestURI);
        
        try {
            // 检查用户是否已登录
            if (StpUtil.isLogin()) {
                // 获取用户ID和角色信息
                Long userId = Long.valueOf(StpUtil.getLoginId().toString());
                String userRole = getUserRole(userId);
                
                // 将用户信息存入请求属性
                request.setAttribute(Const.ATTR_USER_ID, userId);
                request.setAttribute(Const.ATTR_USER_ROLE, userRole);
                
                // 将用户信息放入 MDC 上下文，便于日志记录
                SecurityUtils.setUserIdToMDC(userId);
                SecurityUtils.setUserRoleToMDC(userRole);
                
                log.debug("用户认证成功: userId={}, role={}, uri={}", userId, userRole, requestURI);
            } else {
                log.debug("用户未登录，请求路径: {}", requestURI);
            }
            
            return true;
        } catch (Exception e) {
            log.error("SA-Token 认证拦截器处理异常: {}", e.getMessage(), e);
            return true; // 继续执行，让具体的权限验证在控制器层处理
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求结束后清理 MDC 上下文
        SecurityUtils.clearMDC();
    }

    /**
     * 获取用户角色
     * 
     * @param userId 用户ID
     * @return 用户角色
     */
    private String getUserRole(Long userId) {
        try {
            // 从 SA-Token 的 Session 中获取角色信息
            Object roleObj = StpUtil.getSession().get("role");
            if (roleObj != null) {
                return roleObj.toString();
            }
            
            // 如果 Session 中没有角色信息，从数据库查询
            return SecurityUtils.getLoginUserRole().getRole();
        } catch (Exception e) {
            log.warn("获取用户角色失败，userId: {}, 使用默认角色", userId, e);
            return Const.ROLE_DEFAULT;
        }
    }
}
