package cn.flying.identity.util;

import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.springframework.http.HttpStatus;

/**
 * 响应转换工具类
 * 将旧的Result对象转换为符合RESTful规范的RestResponse对象
 *
 * @author 王贝强
 * @since 2025-01-16
 */
public class ResponseConverter {

    /**
     * 将Result转换为RestResponse
     */
    public static <T> RestResponse<T> convert(Result<T> result) {
        if (result == null) {
            return RestResponse.internalServerError(ResultEnum.SYSTEM_ERROR.getCode(), "系统内部错误");
        }

        // 成功响应
        if (result.isSuccess()) {
            return RestResponse.ok(result.getMessage(), result.getData());
        }

        // 根据错误码映射到对应的HTTP状态码
        HttpStatus httpStatus = mapToHttpStatus(result.getCode());
        return new RestResponse<>(httpStatus, result.getCode(), result.getMessage(), result.getData());
    }

    /**
     * 根据业务错误码映射到HTTP状态码
     */
    private static HttpStatus mapToHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 参数错误：10001-19999 -> 400 Bad Request
        if (code >= 10001 && code <= 19999) {
            return HttpStatus.BAD_REQUEST;
        }

        // 用户错误：20001-29999
        if (code >= 20001 && code <= 29999) {
            return switch (code) {
                case 20001 -> // USER_NOT_LOGGED_IN
                        HttpStatus.UNAUTHORIZED;
                case 20002 -> // USER_LOGIN_ERROR
                        HttpStatus.UNAUTHORIZED;
                case 20003 -> // USER_ACCOUNT_FORBIDDEN
                        HttpStatus.FORBIDDEN;
                case 20004 -> // USER_NOT_EXIST
                        HttpStatus.NOT_FOUND;
                case 20005 -> // USER_HAS_EXISTED
                        HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_REQUEST;
            };
        }

        // 业务错误：30000-39999 -> 500 或其他
        if (code >= 30000 && code <= 39999) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 系统错误：40001-49999
        if (code >= 40001 && code <= 49999) {
            return switch (code) {
                case 40003 -> // FILE_MAX_SIZE_OVERFLOW
                        HttpStatus.PAYLOAD_TOO_LARGE;
                case 40004 -> // FILE_ACCEPT_NOT_SUPPORT
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }

        // 数据错误：50001-59999
        if (code >= 50001 && code <= 59999) {
            return switch (code) {
                case 50001 -> // RESULT_DATA_NONE
                        HttpStatus.NOT_FOUND;
                case 50003 -> // DATA_ALREADY_EXISTED
                        HttpStatus.CONFLICT;
                case 50004 -> // AUTH_CODE_ERROR
                        HttpStatus.UNAUTHORIZED;
                case 50008 -> // FILE_NOT_EXIST
                        HttpStatus.NOT_FOUND;
                default -> HttpStatus.BAD_REQUEST;
            };
        }

        // SSO和OAuth错误：60001-69999
        if (code >= 60001 && code <= 69999) {
            return HttpStatus.UNAUTHORIZED;
        }

        // 权限错误：70001-79999
        if (code >= 70001 && code <= 79999) {
            return switch (code) { // PERMISSION_UNAUTHENTICATED
                // PERMISSION_EXPIRE
                // PERMISSION_TOKEN_EXPIRED
                case 70001, 70003, 70004, 70006 -> // PERMISSION_TOKEN_INVALID
                        HttpStatus.UNAUTHORIZED; // PERMISSION_UNAUTHORIZED
                case 70002, 70005 -> // PERMISSION_LIMIT
                        HttpStatus.FORBIDDEN;
                default -> HttpStatus.FORBIDDEN;
            };
        }

        // API网关错误：80001-89999
        if (code >= 80001 && code <= 89999) {
            return switch (code) {
                case 80002 -> // SERVICE_UNAVAILABLE
                        HttpStatus.SERVICE_UNAVAILABLE;
                case 80004 -> // PARAMETER_ERROR
                        HttpStatus.BAD_REQUEST;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        }

        // 系统错误：90001-99999
        if (code >= 90001 && code <= 99999) {
            if (code == 90002) { // SYSTEM_BUSY
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // 默认为500
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * 根据ResultEnum创建错误RestResponse
     */
    public static <T> RestResponse<T> error(ResultEnum resultEnum) {
        return fromResultEnum(resultEnum, null);
    }

    /**
     * 根据ResultEnum创建RestResponse
     */
    public static <T> RestResponse<T> fromResultEnum(ResultEnum resultEnum, T data) {
        HttpStatus httpStatus = mapToHttpStatus(resultEnum.getCode());
        return new RestResponse<>(httpStatus, resultEnum.getCode(), resultEnum.getMessage(), data);
    }
}
