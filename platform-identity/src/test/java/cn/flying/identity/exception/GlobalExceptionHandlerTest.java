package cn.flying.identity.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.flying.identity.vo.RestResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 单元测试
 * 测试全局异常处理功能
 *
 * @author 王贝强
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private HttpServletRequest request;

    /**
     * 测试前准备
     */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(exceptionHandler, "activeProfile", "dev");
        when(request.getRequestURI()).thenReturn("/test/api");
    }

    @Test
    void testHandleNoHandlerFoundException() {
        NoHandlerFoundException exception = new NoHandlerFoundException("GET", "/test/api", null);

        ResponseEntity<RestResponse<Void>> response = exceptionHandler.handleNoHandlerFoundException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("请求的资源不存在"));
    }

    @Test
    void testHandleNotLoginException_NoToken() {
        NotLoginException exception = mock(NotLoginException.class);
        when(exception.getType()).thenReturn(NotLoginException.NOT_TOKEN);

        ResponseEntity<RestResponse<Void>> response = exceptionHandler.handleNotLoginException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("无效的认证令牌"));
    }

    @Test
    void testHandleNotLoginException_TokenTimeout() {
        NotLoginException exception = mock(NotLoginException.class);
        when(exception.getType()).thenReturn(NotLoginException.TOKEN_TIMEOUT);

        ResponseEntity<RestResponse<Void>> response = exceptionHandler.handleNotLoginException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("认证令牌已过期"));
    }

    @Test
    void testHandleNotPermissionException() {
        NotPermissionException exception = new NotPermissionException("admin");

        ResponseEntity<RestResponse<Void>> response = exceptionHandler.handlePermissionException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("权限不足"));
    }

    @Test
    void testHandleHttpMessageNotReadableException() {
        HttpMessageNotReadableException exception = mock(HttpMessageNotReadableException.class);
        when(exception.getMessage()).thenReturn("请求参数格式错误");

        ResponseEntity<RestResponse<Void>> response = exceptionHandler.handleValidationException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("请求参数格式错误"));
    }

    @Test
    void testHandleHttpRequestMethodNotSupportedException() {
        HttpRequestMethodNotSupportedException exception = mock(HttpRequestMethodNotSupportedException.class);
        when(exception.getMethod()).thenReturn("POST");
        when(exception.getSupportedMethods()).thenReturn(new String[]{"GET", "PUT"});

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleMethodNotSupportedException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("不支持的请求方法"));
    }

    @Test
    void testHandleMaxUploadSizeExceededException() {
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(1000000);

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleMaxUploadSizeExceededException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("上传文件大小超过限制"));
    }

    @Test
    void testHandleRateLimitException() {
        RateLimitException exception = new RateLimitException("请求过于频繁");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleRateLimitException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("请求过于频繁"));
    }

    @Test
    void testHandleBusinessException() {
        BusinessException exception = new BusinessException(10001, "业务异常");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleBusinessException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("业务异常", response.getBody().getMessage());
    }

    @Test
    void testHandleUnsupportedOperationException() {
        UnsupportedOperationException exception = new UnsupportedOperationException("功能未实现");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleNotImplementedException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("该功能暂未实现"));
    }

    @Test
    void testHandleServiceUnavailableException() {
        ServiceUnavailableException exception = new ServiceUnavailableException("服务不可用");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleServiceUnavailableException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("服务暂时不可用"));
    }

    @Test
    void testHandleDataAccessException() {
        DataAccessException exception = mock(DataAccessException.class);
        when(exception.getMostSpecificCause()).thenReturn(new RuntimeException("数据库连接失败"));

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleDataAccessException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("数据库访问异常"));
    }

    @Test
    void testHandleRuntimeException() {
        RuntimeException exception = new RuntimeException("运行时异常");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleRuntimeException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("系统内部错误"));
    }

    @Test
    void testHandleException() {
        Exception exception = new Exception("未知异常");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getMessage().contains("系统繁忙"));
    }

    @Test
    void testHandleException_ProductionProfile() {
        // 修改profile为生产环境
        ReflectionTestUtils.setField(exceptionHandler, "activeProfile", "prod");

        RuntimeException exception = new RuntimeException("内部错误");

        ResponseEntity<RestResponse<Void>> response =
                exceptionHandler.handleRuntimeException(exception, request);

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        // 生产环境不应该暴露详细错误信息
        assertNull(response.getBody().getError());
    }
}
