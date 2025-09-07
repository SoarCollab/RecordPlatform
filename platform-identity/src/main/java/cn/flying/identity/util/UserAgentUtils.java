package cn.flying.identity.util;

import jakarta.servlet.http.HttpServletRequest;
import cn.hutool.core.util.StrUtil;

/**
 * User-Agent工具类
 * 用于解析客户端浏览器和操作系统信息
 * 
 * @author flying
 * @date 2024
 */
public class UserAgentUtils {

    private static final String UNKNOWN = "Unknown";

    /**
     * 获取User-Agent字符串
     * 
     * @param request HTTP请求对象
     * @return User-Agent字符串
     */
    public static String getUserAgent(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        
        String userAgent = request.getHeader("User-Agent");
        return StrUtil.isNotBlank(userAgent) ? userAgent : UNKNOWN;
    }

    /**
     * 获取浏览器信息
     * 
     * @param request HTTP请求对象
     * @return 浏览器信息
     */
    public static String getBrowser(HttpServletRequest request) {
        String userAgent = getUserAgent(request);
        return getBrowserFromUserAgent(userAgent);
    }

    /**
     * 从User-Agent字符串中解析浏览器信息
     * 
     * @param userAgent User-Agent字符串
     * @return 浏览器信息
     */
    public static String getBrowserFromUserAgent(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return UNKNOWN;
        }

        userAgent = userAgent.toLowerCase();

        if (userAgent.contains("edg")) {
            return "Microsoft Edge";
        } else if (userAgent.contains("chrome")) {
            return "Google Chrome";
        } else if (userAgent.contains("firefox")) {
            return "Mozilla Firefox";
        } else if (userAgent.contains("safari") && !userAgent.contains("chrome")) {
            return "Safari";
        } else if (userAgent.contains("opera") || userAgent.contains("opr")) {
            return "Opera";
        } else if (userAgent.contains("msie") || userAgent.contains("trident")) {
            return "Internet Explorer";
        } else if (userAgent.contains("postman")) {
            return "Postman";
        } else if (userAgent.contains("curl")) {
            return "cURL";
        } else if (userAgent.contains("wget")) {
            return "Wget";
        } else {
            return UNKNOWN;
        }
    }

    /**
     * 获取操作系统信息
     * 
     * @param request HTTP请求对象
     * @return 操作系统信息
     */
    public static String getOperatingSystem(HttpServletRequest request) {
        String userAgent = getUserAgent(request);
        return getOperatingSystemFromUserAgent(userAgent);
    }

    /**
     * 从User-Agent字符串中解析操作系统信息
     * 
     * @param userAgent User-Agent字符串
     * @return 操作系统信息
     */
    public static String getOperatingSystemFromUserAgent(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return UNKNOWN;
        }

        userAgent = userAgent.toLowerCase();

        if (userAgent.contains("windows nt 10")) {
            return "Windows 10";
        } else if (userAgent.contains("windows nt 6.3")) {
            return "Windows 8.1";
        } else if (userAgent.contains("windows nt 6.2")) {
            return "Windows 8";
        } else if (userAgent.contains("windows nt 6.1")) {
            return "Windows 7";
        } else if (userAgent.contains("windows nt 6.0")) {
            return "Windows Vista";
        } else if (userAgent.contains("windows nt 5.1")) {
            return "Windows XP";
        } else if (userAgent.contains("windows")) {
            return "Windows";
        } else if (userAgent.contains("mac os x")) {
            return "macOS";
        } else if (userAgent.contains("linux")) {
            if (userAgent.contains("android")) {
                return "Android";
            } else {
                return "Linux";
            }
        } else if (userAgent.contains("iphone") || userAgent.contains("ipad")) {
            return "iOS";
        } else if (userAgent.contains("unix")) {
            return "Unix";
        } else {
            return UNKNOWN;
        }
    }

    /**
     * 判断是否为移动设备
     * 
     * @param request HTTP请求对象
     * @return 是否为移动设备
     */
    public static boolean isMobile(HttpServletRequest request) {
        String userAgent = getUserAgent(request);
        return isMobileFromUserAgent(userAgent);
    }

    /**
     * 从User-Agent字符串中判断是否为移动设备
     * 
     * @param userAgent User-Agent字符串
     * @return 是否为移动设备
     */
    public static boolean isMobileFromUserAgent(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return false;
        }

        userAgent = userAgent.toLowerCase();
        
        String[] mobileKeywords = {
            "mobile", "android", "iphone", "ipad", "ipod", "blackberry",
            "windows phone", "opera mini", "opera mobi", "palm", "symbian"
        };

        for (String keyword : mobileKeywords) {
            if (userAgent.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取设备类型
     * 
     * @param request HTTP请求对象
     * @return 设备类型
     */
    public static String getDeviceType(HttpServletRequest request) {
        String userAgent = getUserAgent(request);
        return getDeviceTypeFromUserAgent(userAgent);
    }

    /**
     * 从User-Agent字符串中获取设备类型
     * 
     * @param userAgent User-Agent字符串
     * @return 设备类型
     */
    public static String getDeviceTypeFromUserAgent(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return UNKNOWN;
        }

        userAgent = userAgent.toLowerCase();

        if (userAgent.contains("ipad") || userAgent.contains("tablet")) {
            return "Tablet";
        } else if (isMobileFromUserAgent(userAgent)) {
            return "Mobile";
        } else {
            return "Desktop";
        }
    }

    /**
     * 获取完整的客户端信息
     * 
     * @param request HTTP请求对象
     * @return 客户端信息字符串
     */
    public static String getClientInfo(HttpServletRequest request) {
        String browser = getBrowser(request);
        String os = getOperatingSystem(request);
        String deviceType = getDeviceType(request);
        
        return String.format("%s on %s (%s)", browser, os, deviceType);
    }
}