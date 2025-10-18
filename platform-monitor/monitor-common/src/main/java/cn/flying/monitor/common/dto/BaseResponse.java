package cn.flying.monitor.common.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * 统一响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BaseResponse<T> {
    
    private boolean success;
    private String message;
    private T data;
    private String traceId;
    private Instant timestamp;
    
    public static <T> BaseResponse<T> success(T data) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setSuccess(true);
        response.setMessage("操作成功");
        response.setData(data);
        response.setTimestamp(Instant.now());
        return response;
    }
    
    public static <T> BaseResponse<T> success(T data, String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setSuccess(true);
        response.setMessage(message);
        response.setData(data);
        response.setTimestamp(Instant.now());
        return response;
    }
    
    public static <T> BaseResponse<T> error(String message) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setSuccess(false);
        response.setMessage(message);
        response.setTimestamp(Instant.now());
        return response;
    }
    
    public static <T> BaseResponse<T> error(String message, String traceId) {
        BaseResponse<T> response = new BaseResponse<>();
        response.setSuccess(false);
        response.setMessage(message);
        response.setTraceId(traceId);
        response.setTimestamp(Instant.now());
        return response;
    }
}