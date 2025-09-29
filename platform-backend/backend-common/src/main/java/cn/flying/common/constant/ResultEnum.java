package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.io.Serializable;

/**
 * @program: RecordPlatform
 * @description: 返回结果枚举
 * @author: 王贝强
 * @create: 2025-01-15 15:36
 */
@Getter
@Schema(description = "返回结果枚举")
public enum ResultEnum implements Serializable {

    /* 成功状态码 */
    SUCCESS(1, "操作成功!"),

    /* 错误状态码 */
    FAIL(0, "服务器内部错误，请联系管理员!"),

    /* 参数错误：10001-19999 */
    PARAM_IS_INVALID(10001, "参数无效"),
    PARAM_IS_BLANK(10002, "参数为空"),
    PARAM_TYPE_BIND_ERROR(10003, "参数格式错误"),
    PARAM_NOT_COMPLETE(10004, "参数缺失"),

    /* 用户错误：20001-29999*/
    USER_NOT_LOGGED_IN(20001, "用户未登录，请先登录"),
    USER_LOGIN_ERROR(20002, "账号不存在或密码错误"),
    USER_ACCOUNT_FORBIDDEN(20003, "账号已被禁用"),
    USER_NOT_EXIST(20004, "用户不存在"),
    USER_HAS_EXISTED(20005, "用户已存在"),

    /* 系统错误：40001-49999 */
    FILE_MAX_SIZE_OVERFLOW(40003, "上传尺寸过大"),
    FILE_ACCEPT_NOT_SUPPORT(40004, "上传文件格式不支持"),

    /* 数据错误：50001-599999 */
    RESULT_DATA_NONE(50001, "数据未找到"),
    DATA_IS_WRONG(50002, "数据有误"),
    DATA_ALREADY_EXISTED(50003, "数据已存在"),
    AUTH_CODE_ERROR(50004, "验证码错误"),
    FILE_UPLOAD_ERROR(50005, "文件上传失败"),
    FILE_DOWNLOAD_ERROR(50006, "文件下载失败"),
    FILE_DELETE_ERROR(50007, "文件删除失败"),
    FILE_NOT_EXIST(50008, "文件不存在"),
    FILE_EMPTY(50009, "文件为空"),
    JSON_PARSE_ERROR(50010, "JSON格式化失败"),
    FILE_RECORD_ERROR(50011, "文件存证失败"),

    /* 接口错误：60001-69999 */
    INTERFACE_NOT_FOUND(60001, "接口不存在"),
    INTERFACE_METHOD_NOT_ALLOWED(60002, "请求方法不支持"),
    INTERFACE_REQUEST_TIMEOUT(60003, "接口请求超时"),
    INTERFACE_EXCEED_LIMIT(60004, "接口访问频率超限"),

    /* 权限错误：70001-79999 */
    PERMISSION_UNAUTHENTICATED(70001, "此操作需要登陆系统!"),
    PERMISSION_UNAUTHORIZED(70002, "权限不足，无权操作!"),
    PERMISSION_EXPIRE(70003, "登录状态过期！"),
    PERMISSION_TOKEN_EXPIRED(70004, "token已过期"),
    PERMISSION_LIMIT(70005, "访问次数受限制"),
    PERMISSION_TOKEN_INVALID(70006, "无效token"),
    PERMISSION_SIGNATURE_ERROR(70007, "签名失败"),

    /* 业务错误：80001-89999 */
    SYSTEM_ERROR(80001, "系统内部错误"),
    DATABASE_ERROR(80002, "数据库操作异常"),
    CACHE_ERROR(80003, "缓存操作异常"),
    FILE_SERVICE_ERROR(80004, "文件服务异常"),
    PARAM_TYPE_ERROR(80005, "参数类型错误"),
    FILE_SIZE_EXCEED(80006, "文件大小超过限制");

    // 状态码
    @Schema(description = "状态码")
    int code;
    // 提示信息
    @Schema(description = "提示信息")
    String message;

    ResultEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
