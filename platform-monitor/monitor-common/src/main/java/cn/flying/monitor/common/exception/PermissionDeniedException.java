package cn.flying.monitor.common.exception;

/**
 * Exception thrown when user lacks required permissions
 */
public class PermissionDeniedException extends RuntimeException {
    
    public PermissionDeniedException(String message) {
        super(message);
    }
    
    public PermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}