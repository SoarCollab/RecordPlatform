package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.io.Serializable;

/**
 * 统一返回结果枚举 v1.1.0
 * 编码规范：
 * - 200: 成功
 * - 400-499: 客户端错误（参数、认证、权限）
 * - 500-599: 服务端错误
 * - 10000-19999: 参数校验错误
 * - 20000-29999: 用户/认证错误
 * - 30000-39999: 外部服务错误（区块链、存储）
 * - 40000-49999: 系统内部错误
 * - 50000-59999: 业务数据错误
 * - 70000-79999: 权限控制错误
 */
@Getter
@Schema(description = "返回结果枚举")
public enum ResultEnum implements Serializable {

    /* ==================== 通用状态码 ==================== */
    /** 操作成功 */
    @Schema(description = "操作成功")
    SUCCESS(200, "操作成功"),
    /** 服务器内部错误 */
    @Schema(description = "服务器内部错误")
    FAIL(500, "服务器内部错误，请联系管理员"),

    /* ==================== 参数错误：10000-19999 ==================== */
    /** 参数错误（通用） */
    PARAM_ERROR(10000, "参数错误"),
    /** 参数无效 */
    PARAM_IS_INVALID(10001, "参数无效"),
    /** 参数为空 */
    PARAM_IS_BLANK(10002, "参数为空"),
    /** 参数格式错误 */
    PARAM_TYPE_BIND_ERROR(10003, "参数格式错误"),
    /** 参数缺失 */
    PARAM_NOT_COMPLETE(10004, "参数缺失"),
    /** JSON解析错误 */
    JSON_PARSE_ERROR(10005, "JSON格式化失败"),

    /* ==================== 用户错误：20000-29999 ==================== */
    /** 用户未登录 */
    USER_NOT_LOGGED_IN(20001, "用户未登录，请先登录"),
    /** 登录失败 */
    USER_LOGIN_ERROR(20002, "账号不存在或密码错误"),
    /** 账号禁用 */
    USER_ACCOUNT_FORBIDDEN(20003, "账号已被禁用"),
    /** 用户不存在 */
    USER_NOT_EXIST(20004, "用户不存在"),
    /** 用户已存在 */
    USER_HAS_EXISTED(20005, "用户已存在"),
    /** 账户已锁定 */
    USER_ACCOUNT_LOCKED(20006, "登录失败次数过多，账户已被临时锁定，请稍后重试"),

    /* ==================== 外部服务错误：30000-39999 ==================== */
    /** 合约调用失败 */
    CONTRACT_ERROR(30001, "合约调用失败"),
    /** 合约返回值错误 */
    INVALID_RETURN_VALUE(30002, "合约返回值错误"),
    /** 获取用户文件失败 */
    GET_USER_FILE_ERROR(30003, "获取用户文件失败"),
    /** 删除用户文件失败 */
    DELETE_USER_FILE_ERROR(30004, "删除用户文件失败"),
    /** 获取分享文件失败 */
    GET_USER_SHARE_FILE_ERROR(30005, "获取分享文件失败，文件不存在或访问次数受限"),
    /** 区块链服务异常 */
    BLOCKCHAIN_ERROR(30006, "区块链服务请求失败"),
    /** 交易记录未找到 */
    TRANSACTION_NOT_FOUND(30007, "交易记录未找到"),
    /** 交易回执未找到 */
    TRANSACTION_RECEIPT_NOT_FOUND(30008, "交易记录回执未找到"),
    /** 文件服务异常 */
    FILE_SERVICE_ERROR(30009, "文件服务请求失败"),
    /** 服务熔断 */
    SERVICE_CIRCUIT_OPEN(30010, "服务暂时不可用，请稍后重试"),
    /** 服务超时 */
    SERVICE_TIMEOUT(30011, "服务响应超时"),

    /* ==================== 系统错误：40000-49999 ==================== */
    /** 文件大小超限 */
    FILE_MAX_SIZE_OVERFLOW(40001, "上传尺寸过大"),
    /** 文件格式不支持 */
    FILE_ACCEPT_NOT_SUPPORT(40002, "上传文件格式不支持"),
    /** 系统繁忙 */
    SYSTEM_BUSY(40003, "系统繁忙，请稍后重试"),
    /** 请求限流 */
    RATE_LIMIT_EXCEEDED(40004, "请求过于频繁，请稍后重试"),
    /** 服务不可用（可重试） */
    SERVICE_UNAVAILABLE(40005, "服务暂时不可用，请稍后重试"),

    /* ==================== 数据错误：50000-59999 ==================== */
    /** 数据未找到 */
    RESULT_DATA_NONE(50001, "数据未找到"),
    /** 数据有误 */
    DATA_IS_WRONG(50002, "数据有误"),
    /** 数据已存在 */
    DATA_ALREADY_EXISTED(50003, "数据已存在"),
    /** 验证码错误 */
    AUTH_CODE_ERROR(50004, "验证码错误"),
    /** 文件上传失败 */
    FILE_UPLOAD_ERROR(50005, "文件上传失败"),
    /** 文件下载失败 */
    FILE_DOWNLOAD_ERROR(50006, "文件下载失败"),
    /** 文件删除失败 */
    FILE_DELETE_ERROR(50007, "文件删除失败"),
    /** 文件不存在 */
    FILE_NOT_EXIST(50008, "文件不存在"),
    /** 文件为空 */
    FILE_EMPTY(50009, "文件为空"),
    /** 文件存证失败 */
    FILE_RECORD_ERROR(50010, "文件存证失败"),

    /* ==================== 消息服务错误：60000-69999 ==================== */
    /** 消息不存在 */
    MESSAGE_NOT_FOUND(60001, "消息不存在"),
    /** 会话不存在 */
    CONVERSATION_NOT_FOUND(60002, "会话不存在"),
    /** 不能给自己发送消息 */
    CANNOT_MESSAGE_SELF(60003, "不能给自己发送消息"),
    /** 公告不存在 */
    ANNOUNCEMENT_NOT_FOUND(60004, "公告不存在"),
    /** 工单不存在 */
    TICKET_NOT_FOUND(60005, "工单不存在"),
    /** 工单已关闭 */
    TICKET_ALREADY_CLOSED(60006, "工单已关闭，无法操作"),
    /** 无权操作工单 */
    TICKET_NOT_OWNER(60007, "无权操作此工单"),
    /** 工单状态无效 */
    INVALID_TICKET_STATUS(60008, "工单状态无效"),
    /** 附件数量超限 */
    ATTACHMENT_LIMIT_EXCEEDED(60009, "附件数量超过限制"),

    /* ==================== 权限错误：70000-79999 ==================== */
    /** 需要登录 */
    PERMISSION_UNAUTHENTICATED(70001, "此操作需要登录系统"),
    /** 权限不足 */
    PERMISSION_UNAUTHORIZED(70002, "权限不足，无权操作"),
    /** Token过期 */
    PERMISSION_TOKEN_EXPIRED(70004, "Token已过期"),
    /** 访问受限 */
    PERMISSION_LIMIT(70005, "访问次数受限制"),
    /** Token无效 */
    PERMISSION_TOKEN_INVALID(70006, "无效Token"),
    /** 签名错误 */
    PERMISSION_SIGNATURE_ERROR(70007, "签名验证失败");

    /** 状态码 */
    @Schema(description = "状态码")
    private final int code;
    /** 提示信息 */
    @Schema(description = "提示信息")
    private final String message;

    ResultEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据状态码查找枚举
     */
    public static ResultEnum fromCode(int code) {
        for (ResultEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return FAIL;
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
