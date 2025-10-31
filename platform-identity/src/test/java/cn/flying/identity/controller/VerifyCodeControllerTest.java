package cn.flying.identity.controller;

import cn.flying.identity.exception.GlobalExceptionHandler;
import cn.flying.identity.service.VerifyCodeService;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VerifyCodeController validation tests.
 */
@WebMvcTest(VerifyCodeController.class)
@Import(GlobalExceptionHandler.class)
class VerifyCodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VerifyCodeService verifyCodeService;

    @Test
    void deleteCode_BlankParams_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(delete("/api/verification/codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("identifier", " ")
                        .param("type", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()));

        verifyNoInteractions(verifyCodeService);
    }
}
