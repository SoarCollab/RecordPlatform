package cn.flying.filter.handler;

import cn.flying.common.constant.ErrorPayload;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValidationControllerTest {

    @Test
    @DisplayName("should return PARAM_IS_INVALID with exception message")
    void shouldReturnParamIsInvalidWithExceptionMessage() {
        ValidationController controller = new ValidationController();
        ValidationException ex = new ValidationException("字段不能为空");

        Result<ErrorPayload> result = controller.validateError(ex);

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertNotNull(result.getData());
        assertEquals("字段不能为空", result.getData().getDetail());
    }
}
