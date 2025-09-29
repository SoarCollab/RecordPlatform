package cn.flying.identity.exception;

import lombok.Getter;

/**
 * 服务不可用异常
 * 当服务暂时不可用时抛出
 * 
 * @author 王贝强
 * @since 2025-01-16
 */
@Getter
public class ServiceUnavailableException extends RuntimeException {
    
    private final Integer code;
    
    public ServiceUnavailableException(String message) {
        super(message);
        this.code = 80002; // SERVICE_UNAVAILABLE
    }
    
    public ServiceUnavailableException(Integer code, String message) {
        super(message);
        this.code = code;
    }
    
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
        this.code = 80002;
    }

}
