package cn.flying.monitor.common.exception;

import java.util.Map;

/**
 * 证书验证异常
 */
public class CertificateValidationException extends MonitorException {
    
    private final String errorCode;
    private final Map<String, Object> context;
    
    public CertificateValidationException(String message) {
        super(message);
        this.errorCode = "CERT_VALIDATION_FAILED";
        this.context = null;
    }
    
    public CertificateValidationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.context = null;
    }
    
    public CertificateValidationException(String message, String errorCode, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }
    
    public CertificateValidationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "CERT_VALIDATION_FAILED";
        this.context = null;
    }
    
    public CertificateValidationException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = null;
    }
    
    public CertificateValidationException(String message, String errorCode, Map<String, Object> context, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = context;
    }
    
    public String getErrorCode() {
        return errorCode;
    }
    
    public Map<String, Object> getContext() {
        return context;
    }
}