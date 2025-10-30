package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.exception.BusinessException;
import cn.flying.platformapi.constant.ResultEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * API网关基础控制器
 * 提供公共方法和异常处理
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
public abstract class BaseApiGatewayController {

    /**
     * 获取当前登录用户ID
     * @return 用户ID
     */
    protected Long getCurrentUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            log.error("获取当前用户ID失败", e);
            // 返回null表示未登录，让调用者处理
            return null;
        }
    }

    /**
     * 获取当前登录用户ID（必须登录）
     * @return 用户ID
     * @throws RuntimeException 如果用户未登录
     */
    protected Long requireCurrentUserId() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(ResultEnum.USER_NOT_LOGGED_IN, "用户未登录");
        }
        return userId;
    }

    /**
     * 获取客户端IP地址
     * @param request HTTP请求
     * @return IP地址
     */
    protected String getClientIp(jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 如果是多个代理，取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 记录操作日志
     * @param operation 操作名称
     * @param details 操作详情
     */
    protected void logOperation(String operation, String details) {
        Long userId = getCurrentUserId();
        log.info("操作日志 - 用户: {}, 操作: {}, 详情: {}",
                userId != null ? userId : "anonymous",
                operation,
                details);
    }

    /**
     * 验证参数非空
     * @param value 参数值
     * @param paramName 参数名称
     * @throws BusinessException 如果参数为空
     */
    protected void requireNonNull(Object value, String paramName) {
        if (value == null) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, paramName + "不能为空");
        }
    }

    /**
     * 验证字符串非空
     * @param value 字符串值
     * @param paramName 参数名称
     * @throws BusinessException 如果字符串为空
     */
    protected void requireNonBlank(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(ResultEnum.PARAM_IS_INVALID, paramName + "不能为空");
        }
    }
}
