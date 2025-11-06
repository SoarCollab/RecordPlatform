package cn.flying.identity.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证全局异常处理对 Sa-Token 异常的统一响应
 */
class GlobalExceptionHandlerSecurityTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotLoginExceptionReturns401() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/oauth/clients");

        NotLoginException exception = NotLoginException.newInstance(
                "default", NotLoginException.NOT_TOKEN, NotLoginException.NOT_TOKEN_MESSAGE, null);

        ResponseEntity<?> response = handler.handleNotLoginException(exception, request);
        Assertions.assertEquals(401, response.getStatusCode().value());

        RestResponse<?> body = (RestResponse<?>) response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertEquals(ResultEnum.PERMISSION_TOKEN_INVALID.getCode(), body.getCode());
    }

    @Test
    void handleNotPermissionExceptionReturns403() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/oauth/clients");

        NotPermissionException exception = new NotPermissionException("oauth:client:delete", "default");

        ResponseEntity<?> response = handler.handlePermissionException(exception, request);
        Assertions.assertEquals(403, response.getStatusCode().value());

        RestResponse<?> body = (RestResponse<?>) response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), body.getCode());
    }

    @Test
    void handleNotRoleExceptionReturns403() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/oauth/clients");

        NotRoleException exception = new NotRoleException("admin", "default");

        ResponseEntity<?> response = handler.handlePermissionException(exception, request);
        Assertions.assertEquals(403, response.getStatusCode().value());

        RestResponse<?> body = (RestResponse<?>) response.getBody();
        Assertions.assertNotNull(body);
        Assertions.assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), body.getCode());
    }
}
