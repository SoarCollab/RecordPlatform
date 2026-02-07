package cn.flying.filter.handler;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    /**
     * 验证业务异常响应会优先透传 GeneralException.data 到 payload.detail，避免丢失结构化错误上下文。
     */
    @Test
    @DisplayName("should propagate GeneralException.data as payload.detail")
    void shouldPropagateGeneralExceptionDataAsPayloadDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
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

    /**
     * 验证 Multipart 缺失请求分片时会返回 400 对应的统一错误码与错误载荷。
     */
    @Test
    @DisplayName("should return bad request result for missing multipart request part")
    void shouldReturnBadRequestResultForMissingMultipartRequestPart() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        Result<?> result = handler.handleMissingServletRequestPartException(
                new MissingServletRequestPartException("file"));

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_NOT_COMPLETE.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertEquals("缺少分片参数: file", payload.getDetail());
    }

    /**
     * 验证 Multipart 请求类型错误时会返回 400，并使用稳定的错误细节文本。
     */
    @Test
    @DisplayName("should return bad request result for invalid multipart request")
    void shouldReturnBadRequestResultForInvalidMultipartRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        Result<?> result = handler.handleMultipartException(
                new MultipartException("Current request is not a multipart request"));

        assertNotNull(result);
        assertEquals(ResultEnum.PARAM_NOT_COMPLETE.getCode(), result.getCode());
        assertInstanceOf(ErrorPayload.class, result.getData());
        ErrorPayload payload = (ErrorPayload) result.getData();
        assertEquals("请求必须是 multipart/form-data", payload.getDetail());
    }
}
