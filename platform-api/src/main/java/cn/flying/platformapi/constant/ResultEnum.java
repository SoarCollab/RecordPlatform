package cn.flying.platformapi.constant;

import lombok.Getter;

import java.io.Serializable;

/**
 * @program: RecordPlatform
 * @description: 返回结果枚举
 * @author: 王贝强
 * @create: 2025-01-15 15:36
 */
@Getter
public enum ResultEnum implements Serializable {

    /* 成功状态码 */
    SUCCESS(1, "操作成功!"),

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
    USER_PASSWORD_ENCRYPT_ERROR(20006, "密码加密失败"),
    USER_PASSWORD_VERIFY_ERROR(20007, "密码验证失败"),

    /* 错误状态码 30000-39999 */
    FAIL(0, "服务器内部错误，请联系管理员!"),
    CONTRACT_ERROR(30001, "合约调用失败"),
    INVALID_RETURN_VALUE(30002, "合约返回值错误"),
    GET_USER_FILE_ERROR(30003, "获取用户文件失败"),
    DELETE_USER_FILE_ERROR(30004, "删除用户文件失败"),
    GET_USER_SHARE_FILE_ERROR(30005, "获取分享文件失败，文件不存在或访问次数受限"),
    BLOCKCHAIN_ERROR(30006, "区块链服务请求失败"),
    TRANSACTION_NOT_FOUND(30007, "交易记录未找到"),
    TRANSACTION_RECEIPT_NOT_FOUND(30008, "交易记录回执未找到"),
    FILE_SERVICE_ERROR(30009, "文件服务请求失败"),


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

    /* SSO和OAuth错误：60001-69999 */
    SSO_ERROR(60001, "SSO认证失败"),
    SSO_UNKNOWN_ERROR(60002, "SSO未知错误"),
    OAUTH_ERROR(60003, "OAuth认证失败"),
    OAUTH_CODE_INVALID(60004, "OAuth授权码无效"),
    OAUTH_TOKEN_INVALID(60005, "OAuth令牌无效"),

    /* 权限错误：70001-79999 */
    PERMISSION_UNAUTHENTICATED(70001, "此操作需要登陆系统!"),
    PERMISSION_UNAUTHORIZED(70002, "权限不足，无权操作!"),
    PERMISSION_EXPIRE(70003, "登录状态过期！"),
    PERMISSION_TOKEN_EXPIRED(70004, "token已过期"),
    PERMISSION_LIMIT(70005, "访问次数受限制"),
    PERMISSION_TOKEN_INVALID(70006, "无效token"),
    PERMISSION_SIGNATURE_ERROR(70007, "签名失败"),
    SYSTEM_ERROR(90001, "系统繁忙，请稍后重试！"),
    SYSTEM_BUSY(90002, "系统繁忙，请稍后重试！"),

    /* API网关错误：80001-89999 */
    SERVICE_ERROR(80001,"服务内部错误!"),
    SERVICE_UNAVAILABLE(80002,"服务暂时不可用!"),
    OPERATION_FAILED(80003,"操作失败!"),
    PARAMETER_ERROR(80004,"令牌错误!");

    // 状态码
    int code;
    // 提示信息
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
