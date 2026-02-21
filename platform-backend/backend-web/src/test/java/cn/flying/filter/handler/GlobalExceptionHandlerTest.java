package cn.flying.filter.handler;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentConversionNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ---- existing tests (refactored to use shared handler instance) ----

    @Test
    @DisplayName("should propagate GeneralException.data as payload.detail")
    void shouldPropagateGeneralExceptionDataAsPayloadDetail() {
        Map<String, Object> data = Map.of("context", Map.of("uploadId", "U123", "missingChunks", new int[]{1, 2}));

        GeneralException ex = new GeneralException("上传失败");
        ex.setResultEnum(ResultEnum.FILE_UPLOAD_ERROR);
        ex.setData(data);

        ResponseEntity<Result<?>> response = handler.handleBusinessException(ex);
        assertNotNull(response);
        assertNotNull(response.getBody());

        Result<?> result = response.getBody();
        assertEquals(ResultEnum.FILE_UPLOAD_ERROR.getCode(), result.getCode());
        assertEquals("上传失败", result.getMessage());

        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertEquals(data, payload.getDetail());
    }

    @Test
    @DisplayName("should return bad request result for missing multipart request part")
    void shouldReturnBadRequestResultForMissingMultipartRequestPart() {
        Result<?> result = handler.handleMissingServletRequestPartException(
                new MissingServletRequestPartException("file"));

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_NOT_COMPLETE.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertEquals("缺少分片参数: file", payload.getDetail());
    }

    @Test
    @DisplayName("should return bad request result for invalid multipart request")
    void shouldReturnBadRequestResultForInvalidMultipartRequest() {
        Result<?> result = handler.handleMultipartException(
                new MultipartException("Current request is not a multipart request"));

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_NOT_COMPLETE.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertEquals("请求必须是 multipart/form-data", payload.getDetail());
    }

    @Test
    @DisplayName("should return bad request for method argument type mismatch")
    void shouldReturnBadRequestForMethodArgumentTypeMismatch() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "not-a-number", Integer.class, "chunkNumber", null,
                new NumberFormatException("For input string: not-a-number"));

        Result<?> result = handler.handleMethodArgumentTypeMismatchException(ex);

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertInstanceOf(String.class, payload.getDetail());
        assertTrue(((String) payload.getDetail()).contains("chunkNumber"));
        assertTrue(((String) payload.getDetail()).contains("Integer"));
    }

    @Test
    @DisplayName("should return bad request for method argument conversion not supported")
    void shouldReturnBadRequestForMethodArgumentConversionNotSupported() {
        MethodArgumentConversionNotSupportedException ex = new MethodArgumentConversionNotSupportedException(
                "abc", Long.class, "clientId", null,
                new IllegalStateException("Conversion not supported"));

        Result<?> result = handler.handleMethodArgumentConversionNotSupportedException(ex);

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertInstanceOf(String.class, payload.getDetail());
        assertTrue(((String) payload.getDetail()).contains("clientId"));
        assertTrue(((String) payload.getDetail()).contains("Long"));
    }

    // ---- new tests ----

    @Nested
    @DisplayName("handleMethodArgumentNotValidException")
    class MethodArgumentNotValidTests {

        @Test
        @DisplayName("should join field errors from @Valid @RequestBody")
        void shouldJoinFieldErrors() {
            BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
            bindingResult.addError(new FieldError("request", "title", "不能为空"));
            bindingResult.addError(new FieldError("request", "size", "必须大于0"));
            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

            Result<?> result = handler.handleMethodArgumentNotValidException(ex);

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            String detail = (String) payload.getDetail();
            assertTrue(detail.contains("title: 不能为空"));
            assertTrue(detail.contains("size: 必须大于0"));
        }
    }

    @Nested
    @DisplayName("handleBusinessException edge cases")
    class BusinessExceptionEdgeCaseTests {

        @Test
        @DisplayName("should use data as message when message is null")
        void shouldUseDataAsMessageWhenMessageIsNull() {
            GeneralException ex = new GeneralException((String) null);
            ex.setResultEnum(ResultEnum.PARAM_IS_INVALID);
            ex.setData("missing-field");

            ResponseEntity<Result<?>> response = handler.handleBusinessException(ex);
            Result<?> result = response.getBody();
            assertNotNull(result);
            assertEquals("missing-field", result.getMessage());
        }

        @Test
        @DisplayName("should use resultEnum message as fallback")
        void shouldUseResultEnumMessageAsFallback() {
            GeneralException ex = new GeneralException((String) null);
            ex.setResultEnum(ResultEnum.PARAM_IS_INVALID);

            ResponseEntity<Result<?>> response = handler.handleBusinessException(ex);
            Result<?> result = response.getBody();
            assertNotNull(result);
            assertEquals(ResultEnum.PARAM_IS_INVALID.getMessage(), result.getMessage());
        }

        @Test
        @DisplayName("should return 403 for PERMISSION_UNAUTHORIZED")
        void shouldReturn403ForPermissionUnauthorized() {
            GeneralException ex = new GeneralException("forbidden");
            ex.setResultEnum(ResultEnum.PERMISSION_UNAUTHORIZED);

            ResponseEntity<Result<?>> response = handler.handleBusinessException(ex);
            Result<?> result = response.getBody();
            assertNotNull(result);
            assertEquals(403, result.getCode());
        }

        @Test
        @DisplayName("should infer 404 when message contains '不存在'")
        void shouldInfer404WhenMessageContainsNotFound() {
            GeneralException ex = new GeneralException("文件不存在");

            ResponseEntity<Result<?>> response = handler.handleBusinessException(ex);
            Result<?> result = response.getBody();
            assertNotNull(result);
            assertEquals(404, result.getCode());
        }
    }

    @Nested
    @DisplayName("handleMissingServletRequestParameterException")
    class MissingServletRequestParameterTests {

        @Test
        @DisplayName("should return PARAM_NOT_COMPLETE with parameter name")
        void shouldReturnParamNotCompleteWithParameterName() {
            MissingServletRequestParameterException ex =
                    new MissingServletRequestParameterException("pageSize", "int");

            Result<?> result = handler.handleMissingServletRequestParameterException(ex);

            assertEquals(ResultEnum.PARAM_NOT_COMPLETE.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            assertEquals("缺少参数: pageSize", payload.getDetail());
        }
    }

    @Nested
    @DisplayName("handleAccessDeniedException")
    class AccessDeniedTests {

        @Test
        @DisplayName("should return PERMISSION_UNAUTHORIZED with exception message")
        void shouldReturnPermissionUnauthorizedWithMessage() {
            var ex = new org.springframework.security.access.AccessDeniedException("操作被禁止");

            Result<?> result = handler.handleAccessDeniedException(ex);

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            assertEquals("操作被禁止", payload.getDetail());
        }

        @Test
        @DisplayName("should use default message when exception message is null")
        void shouldUseDefaultMessageWhenNull() {
            var ex = new org.springframework.security.access.AccessDeniedException(null);

            Result<?> result = handler.handleAccessDeniedException(ex);

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            assertEquals("权限不足", payload.getDetail());
        }
    }

    @Nested
    @DisplayName("handleBindException")
    class BindExceptionTests {

        @Test
        @DisplayName("should join field errors with semicolons")
        void shouldJoinFieldErrorsWithSemicolons() {
            BindException ex = new BindException(new Object(), "form");
            ex.addError(new FieldError("form", "name", "不能为空"));
            ex.addError(new FieldError("form", "email", "格式不正确"));

            Result<?> result = handler.handleBindException(ex);

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            String detail = (String) payload.getDetail();
            assertTrue(detail.contains("name: 不能为空"));
            assertTrue(detail.contains("email: 格式不正确"));
        }
    }

    @Nested
    @DisplayName("handleConstraintViolationException")
    class ConstraintViolationTests {

        @Test
        @DisplayName("should format constraint violations with path and message")
        void shouldFormatConstraintViolationsWithPathAndMessage() {
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("username");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("长度必须在2到20之间");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            Result<?> result = handler.handleConstraintViolationException(ex);

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            String detail = (String) payload.getDetail();
            assertTrue(detail.contains("username"));
            assertTrue(detail.contains("长度必须在2到20之间"));
        }

        @Test
        @DisplayName("should handle violation with empty path")
        void shouldHandleViolationWithEmptyPath() {
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path path = mock(Path.class);
            when(path.toString()).thenReturn("");
            when(violation.getPropertyPath()).thenReturn(path);
            when(violation.getMessage()).thenReturn("参数无效");

            ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

            Result<?> result = handler.handleConstraintViolationException(ex);

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
            ErrorPayload payload = (ErrorPayload) result.getData();
            assertEquals("参数无效", payload.getDetail());
        }
    }

    @Nested
    @DisplayName("handleRetryableException")
    class RetryableExceptionTests {

        @Test
        @DisplayName("should return 503 with Retry-After header when ResultEnum present")
        void shouldReturn503WithRetryAfterHeader() {
            RetryableException ex = new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, 10);

            ResponseEntity<Result<?>> response = handler.handleRetryableException(ex);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals("10", response.getHeaders().getFirst("Retry-After"));
            assertNotNull(response.getBody());
            assertEquals(ResultEnum.SERVICE_UNAVAILABLE.getCode(), response.getBody().getCode());
        }

        @Test
        @DisplayName("should use message when no ResultEnum present")
        void shouldUseMessageWhenNoResultEnum() {
            RetryableException ex = new RetryableException("重试一下", 5);

            ResponseEntity<Result<?>> response = handler.handleRetryableException(ex);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals("5", response.getHeaders().getFirst("Retry-After"));
            assertNotNull(response.getBody());
            assertEquals("重试一下", response.getBody().getMessage());
        }
    }

    @Nested
    @DisplayName("handleAsyncRequestTimeoutException")
    class AsyncRequestTimeoutTests {

        @Test
        @DisplayName("should return 200 for SSE URI")
        void shouldReturn200ForSseUri() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/sse/events");
            when(request.getHeader("Accept")).thenReturn("application/json");

            ResponseEntity<Void> response = handler.handleAsyncRequestTimeoutException(
                    new AsyncRequestTimeoutException(), request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("should return 200 for event-stream Accept header")
        void shouldReturn200ForEventStreamAccept() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/notifications");
            when(request.getHeader("Accept")).thenReturn("text/event-stream");

            ResponseEntity<Void> response = handler.handleAsyncRequestTimeoutException(
                    new AsyncRequestTimeoutException(), request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("should return 503 for non-SSE requests")
        void shouldReturn503ForNonSseRequests() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/files");
            when(request.getHeader("Accept")).thenReturn("application/json");

            ResponseEntity<Void> response = handler.handleAsyncRequestTimeoutException(
                    new AsyncRequestTimeoutException(), request);

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("handleIOException")
    class IOExceptionTests {

        @Test
        @DisplayName("should return 200 for SSE URI")
        void shouldReturn200ForSseUri() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/sse/subscribe");
            when(request.getHeader("Accept")).thenReturn(null);

            ResponseEntity<Void> response = handler.handleIOException(
                    new IOException("Broken pipe"), request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
        }

        @Test
        @DisplayName("should return 500 for non-SSE requests")
        void shouldReturn500ForNonSseRequests() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRequestURI()).thenReturn("/api/upload");
            when(request.getHeader("Accept")).thenReturn("application/json");

            ResponseEntity<Void> response = handler.handleIOException(
                    new IOException("Connection reset"), request);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("handleSystemException")
    class SystemExceptionTests {

        @Test
        @DisplayName("should return FAIL with generic error message")
        void shouldReturnFailWithGenericMessage() {
            Result<?> result = handler.handleSystemException(new RuntimeException("unexpected"));

            assertEquals(ResultEnum.FAIL.getCode(), result.getCode());
            assertInstanceOf(ErrorPayload.class, result.getData());
            ErrorPayload payload = (ErrorPayload) result.getData();
            assertEquals("服务器内部错误，请联系管理员", payload.getDetail());
        }
    }
}
