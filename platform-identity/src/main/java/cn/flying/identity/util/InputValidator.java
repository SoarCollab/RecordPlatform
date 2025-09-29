package cn.flying.identity.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 输入验证工具类
 * 提供常见的输入参数验证功能，防止注入攻击和异常输入
 */
@Slf4j
public class InputValidator {

    // 邮箱正则表达式（RFC 5322标准简化版）
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    // 用户名正则：字母数字下划线，4-20位
    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_]{4,20}$");

    // 密码复杂度正则：至少8位，包含大小写字母和数字
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d@$!%*?&#]{8,}$");

    // SQL注入危险字符检测
    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            ".*(;|--|'|\"|\\*|xp_|sp_|exec|execute|insert|delete|update|drop|create|alter|grant|union|select).*",
            Pattern.CASE_INSENSITIVE);

    // XSS攻击危险字符检测
    private static final Pattern XSS_PATTERN = Pattern.compile(
            ".*(<script|<iframe|javascript:|onerror=|onclick=|onload=).*",
            Pattern.CASE_INSENSITIVE);

    /**
     * 验证邮箱格式
     *
     * @param email 邮箱地址
     * @return 是否有效
     */
    public static boolean isValidEmail(String email) {
        if (StrUtil.isBlank(email)) {
            return false;
        }

        // 长度限制
        if (email.length() > 254) {
            log.warn("邮箱地址过长: length={}", email.length());
            return false;
        }

        return EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * 验证用户名格式
     *
     * @param username 用户名
     * @return 是否有效
     */
    public static boolean isValidUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return false;
        }

        return USERNAME_PATTERN.matcher(username).matches();
    }

    /**
     * 验证密码复杂度
     *
     * @param password 密码
     * @return 是否符合要求
     */
    public static boolean isValidPassword(String password) {
        if (StrUtil.isBlank(password)) {
            return false;
        }

        // 长度检查
        if (password.length() < 8 || password.length() > 128) {
            return false;
        }

        // 复杂度检查（可根据需求调整）
        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;

        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            if (Character.isLowerCase(c)) hasLower = true;
            if (Character.isDigit(c)) hasDigit = true;
        }

        return hasUpper && hasLower && hasDigit;
    }

    /**
     * 检测SQL注入风险
     *
     * @param input 输入字符串
     * @return 是否包含危险字符
     */
    public static boolean containsSqlInjection(String input) {
        if (StrUtil.isBlank(input)) {
            return false;
        }

        boolean hasDanger = SQL_INJECTION_PATTERN.matcher(input).matches();
        if (hasDanger) {
            log.warn("检测到SQL注入风险: input={}", sanitizeForLog(input));
        }
        return hasDanger;
    }

    /**
     * 清理日志输出，防止日志注入
     *
     * @param input 输入字符串
     * @return 安全的日志字符串
     */
    private static String sanitizeForLog(String input) {
        if (input == null) {
            return "null";
        }

        // 限制长度
        if (input.length() > 100) {
            input = input.substring(0, 100) + "...";
        }

        // 移除换行符等控制字符
        return input.replaceAll("[\r\n\t]", " ");
    }

    /**
     * 检测XSS攻击风险
     *
     * @param input 输入字符串
     * @return 是否包含危险字符
     */
    public static boolean containsXss(String input) {
        if (StrUtil.isBlank(input)) {
            return false;
        }

        boolean hasDanger = XSS_PATTERN.matcher(input).matches();
        if (hasDanger) {
            log.warn("检测到XSS攻击风险: input={}", sanitizeForLog(input));
        }
        return hasDanger;
    }

    /**
     * 清理输入字符串，移除危险字符
     *
     * @param input 输入字符串
     * @return 清理后的字符串
     */
    public static String sanitizeInput(String input) {
        if (StrUtil.isBlank(input)) {
            return input;
        }

        // 移除HTML标签
        String cleaned = input.replaceAll("<[^>]*>", "");

        // 移除SQL注入常见字符
        cleaned = cleaned.replaceAll("(;|--|'|\"|\\*)", "");

        // 限制长度
        if (cleaned.length() > 1000) {
            cleaned = cleaned.substring(0, 1000);
        }

        return cleaned.trim();
    }

    /**
     * 验证验证码格式（6位数字）
     *
     * @param code 验证码
     * @return 是否有效
     */
    public static boolean isValidVerificationCode(String code) {
        if (StrUtil.isBlank(code)) {
            return false;
        }

        // 必须是6位数字
        return code.matches("^\\d{6}$");
    }

    /**
     * 验证客户端ID格式
     *
     * @param clientId 客户端ID
     * @return 是否有效
     */
    public static boolean isValidClientId(String clientId) {
        if (StrUtil.isBlank(clientId)) {
            return false;
        }

        // 客户端ID：字母数字和下划线，8-32位
        return clientId.matches("^[a-zA-Z0-9_]{8,32}$");
    }

    /**
     * 验证重定向URI
     *
     * @param uri URI地址
     * @return 是否有效
     */
    public static boolean isValidRedirectUri(String uri) {
        if (StrUtil.isBlank(uri)) {
            return false;
        }

        // 必须是http或https协议
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            return false;
        }

        // 不允许包含特殊字符
        if (uri.contains("..") || uri.contains("\\") ||
                uri.contains("<") || uri.contains(">") ||
                uri.contains("\"") || uri.contains("'")) {
            return false;
        }

        // 长度限制
        return uri.length() <= 512;
    }

    /**
     * 验证分页参数
     *
     * @param page 页码
     * @param size 每页大小
     * @return 是否有效
     */
    public static boolean isValidPagination(Integer page, Integer size) {
        if (page == null || size == null) {
            return false;
        }

        // 页码从1开始，最大1000页
        if (page < 1 || page > 1000) {
            return false;
        }

        // 每页大小1-100
        return size >= 1 && size <= 100;
    }
}