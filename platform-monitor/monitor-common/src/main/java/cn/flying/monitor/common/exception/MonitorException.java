package cn.flying.monitor.common.exception;

/**
 * 监控系统自定义异常
 */
public class MonitorException extends RuntimeException {
    
    public MonitorException(String message) {
        super(message);
    }
    
    public MonitorException(String message, Throwable cause) {
        super(message, cause);
    }
}