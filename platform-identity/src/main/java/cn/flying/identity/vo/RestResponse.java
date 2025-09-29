package cn.flying.identity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RESTful API统一响应包装类
 * 符合行业标准的REST响应格式
 *
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestResponse<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * HTTP状态码
     */
    private Integer status;

    /**
     * 业务状态码（内部错误码）
     */
    private Integer code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    /**
     * 请求路径（用于错误响应）
     */
    private String path;

    /**
     * 错误详情（用于调试，生产环境可隐藏）
     */
    private String error;

    /**
     * 追踪ID（用于日志追踪）
     */
    private String traceId;

    public RestResponse(HttpStatus httpStatus, Integer code, String message, T data) {
        this();
        this.status = httpStatus.value();
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public RestResponse() {
        this.timestamp = LocalDateTime.now();
    }

    // 成功响应构建器
    public static <T> RestResponse<T> ok() {
        return new RestResponse<>(HttpStatus.OK, 1, "操作成功", null);
    }

    public static <T> RestResponse<T> ok(T data) {
        return new RestResponse<>(HttpStatus.OK, 1, "操作成功", data);
    }

    public static <T> RestResponse<T> ok(String message, T data) {
        return new RestResponse<>(HttpStatus.OK, 1, message, data);
    }

    public static <T> RestResponse<T> created(T data) {
        return new RestResponse<>(HttpStatus.CREATED, 1, "创建成功", data);
    }

    public static <T> RestResponse<T> accepted() {
        return new RestResponse<>(HttpStatus.ACCEPTED, 1, "请求已接受", null);
    }

    public static <T> RestResponse<T> noContent() {
        return new RestResponse<>(HttpStatus.NO_CONTENT, 1, "操作成功", null);
    }

    // 错误响应构建器
    public static <T> RestResponse<T> badRequest(Integer code, String message) {
        return new RestResponse<>(HttpStatus.BAD_REQUEST, code, message, null);
    }

    public static <T> RestResponse<T> unauthorized(Integer code, String message) {
        return new RestResponse<>(HttpStatus.UNAUTHORIZED, code, message, null);
    }

    public static <T> RestResponse<T> forbidden(Integer code, String message) {
        return new RestResponse<>(HttpStatus.FORBIDDEN, code, message, null);
    }

    public static <T> RestResponse<T> notFound(Integer code, String message) {
        return new RestResponse<>(HttpStatus.NOT_FOUND, code, message, null);
    }

    public static <T> RestResponse<T> methodNotAllowed(Integer code, String message) {
        return new RestResponse<>(HttpStatus.METHOD_NOT_ALLOWED, code, message, null);
    }

    public static <T> RestResponse<T> conflict(Integer code, String message) {
        return new RestResponse<>(HttpStatus.CONFLICT, code, message, null);
    }

    public static <T> RestResponse<T> unprocessableEntity(Integer code, String message) {
        return new RestResponse<>(HttpStatus.UNPROCESSABLE_ENTITY, code, message, null);
    }

    public static <T> RestResponse<T> tooManyRequests(Integer code, String message) {
        return new RestResponse<>(HttpStatus.TOO_MANY_REQUESTS, code, message, null);
    }

    public static <T> RestResponse<T> internalServerError(Integer code, String message) {
        return new RestResponse<>(HttpStatus.INTERNAL_SERVER_ERROR, code, message, null);
    }

    public static <T> RestResponse<T> notImplemented(Integer code, String message) {
        return new RestResponse<>(HttpStatus.NOT_IMPLEMENTED, code, message, null);
    }

    public static <T> RestResponse<T> badGateway(Integer code, String message) {
        return new RestResponse<>(HttpStatus.BAD_GATEWAY, code, message, null);
    }

    public static <T> RestResponse<T> serviceUnavailable(Integer code, String message) {
        return new RestResponse<>(HttpStatus.SERVICE_UNAVAILABLE, code, message, null);
    }

    public static <T> RestResponse<T> gatewayTimeout(Integer code, String message) {
        return new RestResponse<>(HttpStatus.GATEWAY_TIMEOUT, code, message, null);
    }

    // 带路径和错误详情的错误响应
    public RestResponse<T> withPath(String path) {
        this.path = path;
        return this;
    }

    public RestResponse<T> withError(String error) {
        this.error = error;
        return this;
    }

    public RestResponse<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    /**
     * 判断响应是否成功
     */
    public boolean isSuccess() {
        return this.status != null && this.status >= 200 && this.status < 300;
    }
}
