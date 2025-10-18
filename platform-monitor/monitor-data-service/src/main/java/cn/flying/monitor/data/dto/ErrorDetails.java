package cn.flying.monitor.data.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Error details for API responses
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetails {
    
    private String code;
    
    private String message;
    
    private String description;
    
    private Map<String, Object> details;
    
    private List<String> validationErrors;
    
    private String stackTrace;
    
    private String path;
    
    private String method;
    
    // Constructors
    public ErrorDetails() {}
    
    public ErrorDetails(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public ErrorDetails(String code, String message, Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.details = details;
    }
    
    public ErrorDetails(String code, String message, String description) {
        this.code = code;
        this.message = message;
        this.description = description;
    }
    
    // Getters and Setters
    public String getCode() {
        return code;
    }
    
    public void setCode(String code) {
        this.code = code;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public Map<String, Object> getDetails() {
        return details;
    }
    
    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
    
    public List<String> getValidationErrors() {
        return validationErrors;
    }
    
    public void setValidationErrors(List<String> validationErrors) {
        this.validationErrors = validationErrors;
    }
    
    public String getStackTrace() {
        return stackTrace;
    }
    
    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }
    
    public String getPath() {
        return path;
    }
    
    public void setPath(String path) {
        this.path = path;
    }
    
    public String getMethod() {
        return method;
    }
    
    public void setMethod(String method) {
        this.method = method;
    }
    
    // Utility methods
    public void addDetail(String key, Object value) {
        if (details == null) {
            details = new java.util.HashMap<>();
        }
        details.put(key, value);
    }
    
    public void addValidationError(String error) {
        if (validationErrors == null) {
            validationErrors = new java.util.ArrayList<>();
        }
        validationErrors.add(error);
    }
    
    public boolean hasDetails() {
        return details != null && !details.isEmpty();
    }
    
    public boolean hasValidationErrors() {
        return validationErrors != null && !validationErrors.isEmpty();
    }
    
    public boolean hasStackTrace() {
        return stackTrace != null && !stackTrace.trim().isEmpty();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorDetails that = (ErrorDetails) o;
        return Objects.equals(code, that.code) &&
               Objects.equals(message, that.message) &&
               Objects.equals(path, that.path) &&
               Objects.equals(method, that.method);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(code, message, path, method);
    }
    
    @Override
    public String toString() {
        return "ErrorDetails{" +
               "code='" + code + '\'' +
               ", message='" + message + '\'' +
               ", hasDetails=" + hasDetails() +
               ", hasValidationErrors=" + hasValidationErrors() +
               ", path='" + path + '\'' +
               ", method='" + method + '\'' +
               '}';
    }
}