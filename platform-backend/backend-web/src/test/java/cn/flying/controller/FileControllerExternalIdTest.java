package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileController 外部 ID 转换测试。
 * 验证删除文件接口的 ID 解密与验证逻辑。
 */
@ExtendWith(MockitoExtension.class)
class FileControllerExternalIdTest {

    @Mock
    private FileQueryService fileQueryService;

    @Mock
    private FileService fileService;

    @Mock
    private ShareAuditService shareAuditService;

    @Mock
    private SecureIdCodec secureIdCodec;

    private FileController controller;

    /**
     * 初始化被测控制器并注入 mock 依赖。
     */
    @BeforeEach
    void setUp() {
        controller = new FileController(fileQueryService, fileService, shareAuditService);
    }

    /**
     * 验证提供有效外部 ID 时能正确转换为内部 ID 并删除文件。
     */
    @Test
    void testDeleteFileById_ValidExternalId() {
        // Given
        String externalId = "encrypted_file_123";
        Long internalId = 100L;

        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", secureIdCodec);
        when(secureIdCodec.fromExternalId(externalId)).thenReturn(internalId);

        // When
        var result = controller.deleteFileById(externalId);

        // Then
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("文件删除成功", result.getData());
        verify(fileService).removeByIds(List.of(internalId));
    }

    /**
     * 验证提供无效外部 ID（解密失败返回 null）时抛出 GeneralException。
     */
    @Test
    void testDeleteFileById_InvalidExternalId() {
        // Given
        String invalidExternalId = "malformed_encrypted_id";

        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", secureIdCodec);
        when(secureIdCodec.fromExternalId(invalidExternalId)).thenReturn(null);

        // When & Then
        GeneralException ex = assertThrows(GeneralException.class, () ->
                controller.deleteFileById(invalidExternalId));

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getResultEnum().getCode());
        assertEquals("无效的文件ID", ex.getData());
        verify(fileService, never()).removeByIds(anyList());
    }

    /**
     * 验证路径参数为 null 时会抛出异常（边界情况）。
     */
    @Test
    void testDeleteFileById_NullExternalId() {
        // Given
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", secureIdCodec);

        // When & Then
        GeneralException ex = assertThrows(GeneralException.class, () ->
                controller.deleteFileById(null));

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getResultEnum().getCode());
        verify(fileService, never()).removeByIds(anyList());
    }

    /**
     * 验证空字符串外部 ID 会被正确拒绝。
     */
    @Test
    void testDeleteFileById_EmptyExternalId() {
        // Given
        String emptyExternalId = "";

        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", secureIdCodec);

        // When & Then
        GeneralException ex = assertThrows(GeneralException.class, () ->
                controller.deleteFileById(emptyExternalId));

        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getResultEnum().getCode());
        verify(fileService, never()).removeByIds(anyList());
    }
}
