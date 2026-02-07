package cn.flying.filter.handler;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
}
