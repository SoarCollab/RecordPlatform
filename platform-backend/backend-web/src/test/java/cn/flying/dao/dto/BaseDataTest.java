package cn.flying.dao.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BaseData Tests")
class BaseDataTest {

    /**
     * 验证当目标 VO 新增字段在 DTO 中不存在时，映射流程可安全跳过并保留已有字段映射。
     */
    @Test
    @DisplayName("should skip missing dto field and keep compatible mapping")
    void shouldSkipMissingDtoFieldAndKeepCompatibleMapping() {
        CompatibleDto dto = new CompatibleDto();
        dto.username = "alice";

        CompatibleView result = dto.asViewObject(CompatibleView.class);

        assertEquals("alice", result.username);
        assertNull(result.token);
    }

    /**
     * 验证反射写入异常不会被静默吞掉，会向上抛出非法状态异常。
     */
    @Test
    @DisplayName("should throw when reflective field write fails")
    void shouldThrowWhenReflectiveFieldWriteFails() {
        ImmutableFieldDto dto = new ImmutableFieldDto();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> dto.asViewObject(ImmutableFieldView.class)
        );

        assertTrue(exception.getMessage().contains("IMMUTABLE"));
    }

    /**
     * 模拟 DTO 字段少于目标 VO 字段的兼容场景。
     */
    private static final class CompatibleDto implements BaseData {
        private String username;
    }

    /**
     * 模拟新增 token 字段后的 VO。
     */
    public static final class CompatibleView {
        private String username;
        private String token;

        public CompatibleView() {
        }
    }

    /**
     * 模拟包含不可写静态常量字段的 DTO。
     */
    private static final class ImmutableFieldDto implements BaseData {
        private static final String IMMUTABLE = "dto";
    }

    /**
     * 模拟包含不可写静态常量字段的 VO。
     */
    public static final class ImmutableFieldView {
        private static final String IMMUTABLE = "vo";

        public ImmutableFieldView() {
        }
    }
}
