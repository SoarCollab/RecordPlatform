package cn.flying.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Base62 Tests")
class Base62Test {

    /**
     * 验证包含前导 0 字节的数据在 Base62 编码/解码后仍能保持字节级一致。
     */
    @Test
    @DisplayName("should round-trip bytes with leading zeros")
    void roundTrip_bytesWithLeadingZeros() {
        byte[] data = new byte[42];
        data[0] = 0;
        for (int i = 1; i < data.length; i++) {
            data[i] = (byte) (i * 3);
        }

        String encoded = Base62.encode(data);
        byte[] decoded = Base62.decode(encoded);

        assertThat(decoded).containsExactly(data);
    }

    /**
     * 验证随机字节数组在 Base62 编码/解码过程中不会丢失或新增字节。
     * 该测试使用固定随机种子，保证结果可复现且不受运行环境影响。
     */
    @Test
    @DisplayName("should round-trip random bytes deterministically")
    void roundTrip_randomBytesDeterministically() {
        Random random = new Random(20260205L);

        for (int len = 1; len <= 128; len++) {
            byte[] data = new byte[len];
            random.nextBytes(data);

            String encoded = Base62.encode(data);
            byte[] decoded = Base62.decode(encoded);

            assertThat(decoded)
                    .as("bytes should round-trip correctly for len=%d", len)
                    .containsExactly(data);
        }
    }

    /**
     * 验证空输入与空字符串输入的边界行为符合预期（不抛异常且返回空结果）。
     */
    @Test
    @DisplayName("should handle empty input")
    void handleEmptyInput() {
        assertThat(Base62.encode(new byte[0])).isEqualTo("");
        assertThat(Base62.decode("")).isEmpty();
        assertThat(Base62.decode(null)).isEmpty();
    }
}

