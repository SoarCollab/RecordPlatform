package cn.flying.monitor.server.controller.exceptionController;

import cn.flying.monitor.common.entity.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.error.AbstractErrorController;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 专用用于处理错误页面的Controller
 */
@RestController
@RequestMapping({"${server.error.path:${error.path:/error}}"})
public class ErrorPageController extends AbstractErrorController {

    public ErrorPageController(ErrorAttributes errorAttributes) {
        super(errorAttributes);
    }

    @RequestMapping
    public ResponseEntity<Result<Void>> error(HttpServletRequest request) {
        HttpStatus status = this.getStatus(request);
        Map<String, Object> errorAttributes = this.getErrorAttributes(request, this.getAttributeOptions());
        String message = this.convertErrorMessage(status)
                .orElse(String.valueOf(errorAttributes.getOrDefault("message", "内部错误")));
        return ResponseEntity.status(status)
                .body(Result.error(message));
    }

    private ErrorAttributeOptions getAttributeOptions() {
        return ErrorAttributeOptions
                .defaults()
                .including(ErrorAttributeOptions.Include.MESSAGE,
                        ErrorAttributeOptions.Include.EXCEPTION);
    }

    private Optional<String> convertErrorMessage(HttpStatus status) {
        String value = switch (status.value()) {
            case 400 -> "请求参数有误";
            case 404 -> "请求的接口不存在";
            case 405 -> "请求方法错误";
            case 500 -> "内部错误，请联系管理员";
            default -> null;
        };
        return Optional.ofNullable(value);
    }
}
