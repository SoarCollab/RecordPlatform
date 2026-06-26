package cn.flying.service.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChunkFileHeader 单元测试。
 */
class ChunkFileHeaderTest {

    /**
     * 验证带偏移量的版本化文件头可以被识别并解析出算法标识。
     */
    @Test
    @DisplayName("带偏移量的有效文件头应被识别")
    void hasValidHeaderWithOffset_shouldAcceptVersionedHeader() {
        byte[] data = {
                0x00,
                0x7F,
                ChunkFileHeader.MAGIC[0],
                ChunkFileHeader.MAGIC[1],
                ChunkFileHeader.VERSION,
                ChunkFileHeader.ALGORITHM_CHACHA20,
                0x55
        };

        assertTrue(ChunkFileHeader.hasValidHeader(data, 2));
        assertEquals(ChunkFileHeader.ALGORITHM_CHACHA20, ChunkFileHeader.parseAlgorithm(data, 2));
    }

    /**
     * 验证仅有魔数字节但算法标识不受支持的数据会被拒绝。
     */
    @Test
    @DisplayName("不支持的算法标识应被拒绝")
    void hasValidHeader_shouldRejectUnsupportedAlgorithm() {
        byte[] header = ChunkFileHeader.createHeader((byte) 0x7F);
        byte[] offsetHeader = {
                0x00,
                ChunkFileHeader.MAGIC[0],
                ChunkFileHeader.MAGIC[1],
                ChunkFileHeader.VERSION,
                0x7F
        };

        assertFalse(ChunkFileHeader.hasValidHeader(header));
        assertFalse(ChunkFileHeader.hasValidHeader(offsetHeader, 1));
        assertThrows(IllegalArgumentException.class, () -> ChunkFileHeader.parseAlgorithm(header));
    }
}
