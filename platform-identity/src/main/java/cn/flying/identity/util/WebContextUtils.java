package cn.flying.identity.util;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Web上下文工具类
 * 提供统一的Web上下文操作工具方法，减少重复代码
 * 
 * @author 王贝强
 */
@Slf4j
public class WebContextUtils {
    
    /**
     * 获取当前请求的客户端IP地址
     * 统一处理IP地址获取，避免在多个地方重复相同的逻辑
     * 
     * @return 客户端IP地址，获取失败时返回默认值"127.0.0.1"
     */
    public static String getCurrentClientIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return IpUtils.getClientIp(request);
            }
        } catch (Exception e) {
            log.warn("获取客户端IP失败", e);
        }
        return "127.0.0.1";
    }
    
    /**
     * 获取当前HTTP请求对象
     * 
     * @return HttpServletRequest对象，获取失败时返回null
     */
    public static HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest();
            }
        } catch (Exception e) {
            log.warn("获取当前请求对象失败", e);
        }
        return null;
    }
    /**
     * 检查字符串是否为空
     * 
     * @param str 待检查的字符串
     * @return 是否为空
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 检查字符串是否不为空
     * 
     * @param str 待检查的字符串
     * @return 是否不为空
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }
}
