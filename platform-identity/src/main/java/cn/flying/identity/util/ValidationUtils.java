package cn.flying.identity.util;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 验证工具类
 * 提供常用的参数验证和数据验证方法
 * 
 * @author 王贝强
 */
public class ValidationUtils {

    private ValidationUtils() {}

    // 常用正则表达式
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );
    
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
    );
    
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    /**
     * 验证参数不为空
     * 
     * @param value 待验证的值
     * @param message 错误消息
     * @param <T> 值类型
     * @return 验证结果
     */
    public static <T> Result<T> requireNonNull(T value, String message) {
        if (value == null) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(value);
    }

    /**
     * 验证字符串不为空且不为空白
     * 
     * @param value 待验证的字符串
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireNonBlank(String value, String message) {
        if (CommonUtils.isBlank(value)) {
            return Result.error(message);
        }
        return Result.success(value);
    }

    /**
     * 验证集合不为空
     * 
     * @param collection 待验证的集合
     * @param message 错误消息
     * @param <T> 集合类型
     * @return 验证结果
     */
    public static <T extends Collection<?>> Result<T> requireNonEmpty(T collection, String message) {
        if (CommonUtils.isEmpty(collection)) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(collection);
    }

    /**
     * 验证Map不为空
     * 
     * @param map 待验证的Map
     * @param message 错误消息
     * @param <T> Map类型
     * @return 验证结果
     */
    public static <T extends Map<?, ?>> Result<T> requireNonEmpty(T map, String message) {
        if (CommonUtils.isEmpty(map)) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(map);
    }

    /**
     * 验证数组不为空
     * 
     * @param array 待验证的数组
     * @param message 错误消息
     * @param <T> 数组类型
     * @return 验证结果
     */
    public static <T> Result<T[]> requireNonEmpty(T[] array, String message) {
        if (array == null || array.length == 0) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(array);
    }

    /**
     * 验证条件为真
     * 
     * @param condition 条件
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<Boolean> requireTrue(boolean condition, String message) {
        if (!condition) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(true);
    }

    /**
     * 验证条件为假
     * 
     * @param condition 条件
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<Boolean> requireFalse(boolean condition, String message) {
        if (condition) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(false);
    }

    /**
     * 验证字符串长度在指定范围内
     * 
     * @param value 待验证的字符串
     * @param min 最小长度
     * @param max 最大长度
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireLength(String value, int min, int max, String message) {
        if (value == null || value.length() < min || value.length() > max) {
            return Result.error(message);
        }
        return Result.success(value);
    }

    /**
     * 验证数值在指定范围内
     * 
     * @param value 待验证的数值
     * @param min 最小值
     * @param max 最大值
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<Integer> requireRange(Integer value, int min, int max, String message) {
        if (value == null || value < min || value > max) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(value);
    }

    /**
     * 验证长整数在指定范围内
     * 
     * @param value 待验证的长整数
     * @param min 最小值
     * @param max 最大值
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<Long> requireRange(Long value, long min, long max, String message) {
        if (value == null || value < min || value > max) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(value);
    }

    /**
     * 验证邮箱格式
     * 
     * @param email 待验证的邮箱
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireValidEmail(String email, String message) {
        if (CommonUtils.isBlank(email) || !EMAIL_PATTERN.matcher(email).matches()) {
            return Result.error(message);
        }
        return Result.success(email);
    }

    /**
     * 验证手机号格式
     * 
     * @param phone 待验证的手机号
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireValidPhone(String phone, String message) {
        if (CommonUtils.isBlank(phone) || !PHONE_PATTERN.matcher(phone).matches()) {
            return Result.error(message);
        }
        return Result.success(phone);
    }

    /**
     * 验证URL格式
     * 
     * @param url 待验证的URL
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireValidUrl(String url, String message) {
        if (CommonUtils.isBlank(url) || !URL_PATTERN.matcher(url).matches()) {
            return Result.error(message);
        }
        return Result.success(url);
    }

    /**
     * 验证IPv4地址格式
     * 
     * @param ip 待验证的IP地址
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requireValidIPv4(String ip, String message) {
        if (CommonUtils.isBlank(ip) || !IPV4_PATTERN.matcher(ip).matches()) {
            return Result.error(message);
        }
        return Result.success(ip);
    }

    /**
     * 验证字符串匹配指定正则表达式
     * 
     * @param value 待验证的字符串
     * @param pattern 正则表达式
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requirePattern(String value, String pattern, String message) {
        if (CommonUtils.isBlank(value) || !value.matches(pattern)) {
            return Result.error(message);
        }
        return Result.success(value);
    }

    /**
     * 验证字符串匹配指定Pattern
     * 
     * @param value 待验证的字符串
     * @param pattern Pattern对象
     * @param message 错误消息
     * @return 验证结果
     */
    public static Result<String> requirePattern(String value, Pattern pattern, String message) {
        if (CommonUtils.isBlank(value) || !pattern.matcher(value).matches()) {
            return Result.error(message);
        }
        return Result.success(value);
    }

    /**
     * 自定义验证
     * 
     * @param value 待验证的值
     * @param predicate 验证谓词
     * @param message 错误消息
     * @param <T> 值类型
     * @return 验证结果
     */
    public static <T> Result<T> requireCondition(T value, Predicate<T> predicate, String message) {
        if (value == null || !predicate.test(value)) {
            return new Result<>(ResultEnum.PARAM_IS_INVALID.getCode(), message, null);
        }
        return Result.success(value);
    }

    /**
     * 验证密码强度
     * 
     * @param password 待验证的密码
     * @param minLength 最小长度
     * @param requireDigit 是否要求包含数字
     * @param requireLowercase 是否要求包含小写字母
     * @param requireUppercase 是否要求包含大写字母
     * @param requireSpecialChar 是否要求包含特殊字符
     * @return 验证结果
     */
    public static Result<String> requirePasswordStrength(String password, int minLength, 
                                                         boolean requireDigit, boolean requireLowercase, 
                                                         boolean requireUppercase, boolean requireSpecialChar) {
        if (CommonUtils.isBlank(password)) {
            return Result.error("密码不能为空");
        }
        
        if (password.length() < minLength) {
            return Result.error("密码长度至少为" + minLength + "位");
        }
        
        if (requireDigit && !password.matches(".*\\d.*")) {
            return Result.error("密码必须包含数字");
        }
        
        if (requireLowercase && !password.matches(".*[a-z].*")) {
            return Result.error("密码必须包含小写字母");
        }
        
        if (requireUppercase && !password.matches(".*[A-Z].*")) {
            return Result.error("密码必须包含大写字母");
        }
        
        if (requireSpecialChar && !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            return Result.error("密码必须包含特殊字符");
        }
        
        return Result.success(password);
    }

    /**
     * 批量验证，所有验证都通过才返回成功
     * 
     * @param validations 验证结果数组
     * @return 综合验证结果
     */
    @SafeVarargs
    public static Result<Void> validateAll(Result<?>... validations) {
        for (Result<?> validation : validations) {
            if (ResultUtils.isFailure(validation)) {
                return new Result<>(validation.getCode(), validation.getMessage(), null);
            }
        }
        return Result.success();
    }

    /**
     * 获取预定义的邮箱验证模式
     */
    public static Pattern getEmailPattern() {
        return EMAIL_PATTERN;
    }

    /**
     * 获取预定义的手机号验证模式
     */
    public static Pattern getPhonePattern() {
        return PHONE_PATTERN;
    }

    /**
     * 获取预定义的URL验证模式
     */
    public static Pattern getUrlPattern() {
        return URL_PATTERN;
    }

    /**
     * 获取预定义的IPv4验证模式
     */
    public static Pattern getIPv4Pattern() {
        return IPV4_PATTERN;
    }
}
