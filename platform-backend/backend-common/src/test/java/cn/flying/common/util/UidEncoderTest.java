package cn.flying.common.util;

import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UidEncoder 测试类
 * 测试UID编码工具的核心功能
 */
class UidEncoderTest {

    @Test
    @DisplayName("测试encodeUid基本功能")
    void testEncodeUid_basic() {
        // Given
        String uid = "user123456";

        // When
        String encoded = UidEncoder.encodeUid(uid);

        // Then
        assertNotNull(encoded, "编码结果不应为null");
        assertEquals(12, encoded.length(), "编码长度应为12");
        assertTrue(encoded.matches("[A-Za-z]+"), "编码应该只包含字母");
    }

    @Test
    @DisplayName("测试encodeUid的一致性")
    void testEncodeUid_consistency() {
        // Given
        String uid = "testuser";

        // When
        String encoded1 = UidEncoder.encodeUid(uid);
        String encoded2 = UidEncoder.encodeUid(uid);

        // Then
        assertEquals(encoded1, encoded2, "相同UID的编码应该一致");
    }

    @Test
    @DisplayName("测试encodeUid不同UID生成不同编码")
    void testEncodeUid_differentUids() {
        // Given
        String uid1 = "user1";
        String uid2 = "user2";

        // When
        String encoded1 = UidEncoder.encodeUid(uid1);
        String encoded2 = UidEncoder.encodeUid(uid2);

        // Then
        assertNotEquals(encoded1, encoded2, "不同UID应该生成不同编码");
    }

    @Test
    @DisplayName("测试encodeUid空输入抛出异常")
    void testEncodeUid_nullInput() {
        // When & Then
        assertThrows(GeneralException.class, () -> UidEncoder.encodeUid(null),
                "null输入应该抛出GeneralException");
        assertThrows(GeneralException.class, () -> UidEncoder.encodeUid(""),
                "空字符串输入应该抛出GeneralException");
    }

    @Test
    @DisplayName("测试encodeCid基本功能")
    void testEncodeCid_basic() {
        // Given
        String suid = "encrypted_uid_123";

        // When
        String encoded = UidEncoder.encodeCid(suid);

        // Then
        assertNotNull(encoded, "编码结果不应为null");
        assertEquals(16, encoded.length(), "总长度应为16（4位盐+12位编码）");
        assertTrue(encoded.matches("[A-Za-z]+"), "编码应该只包含字母");
    }

    @Test
    @DisplayName("测试encodeCid每次结果不同（带随机盐）")
    void testEncodeCid_randomness() {
        // Given
        String suid = "test_suid";

        // When
        String encoded1 = UidEncoder.encodeCid(suid);
        String encoded2 = UidEncoder.encodeCid(suid);

        // Then
        assertNotEquals(encoded1, encoded2, "由于随机盐，每次编码应该不同");
        assertEquals(16, encoded1.length(), "两次编码长度都应为16");
        assertEquals(16, encoded2.length(), "两次编码长度都应为16");
    }

    @Test
    @DisplayName("测试encodeCid生成的编码具有唯一性")
    void testEncodeCid_uniqueness() {
        // Given
        String suid = "unique_test";
        Set<String> encodings = new HashSet<>();
        int count = 100;

        // When
        for (int i = 0; i < count; i++) {
            encodings.add(UidEncoder.encodeCid(suid));
        }

        // Then
        assertEquals(count, encodings.size(), "100次编码应该全部唯一");
    }

    @Test
    @DisplayName("测试encodeCid空输入抛出异常")
    void testEncodeCid_nullInput() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> UidEncoder.encodeCid(null),
                "null输入应该抛出IllegalArgumentException");
        assertThrows(IllegalArgumentException.class, () -> UidEncoder.encodeCid(""),
                "空字符串输入应该抛出IllegalArgumentException");
    }

    @Test
    @DisplayName("测试verifyCid验证成功")
    void testVerifyCid_success() {
        // Given
        String suid = "original_suid";
        String encoded = UidEncoder.encodeCid(suid);

        // When
        boolean result = UidEncoder.verifyCid(encoded, suid);

        // Then
        assertTrue(result, "应该能够验证自己生成的编码");
    }

    @Test
    @DisplayName("测试verifyCid验证失败 - 错误的原始值")
    void testVerifyCid_wrongOriginal() {
        // Given
        String suid = "original_suid";
        String encoded = UidEncoder.encodeCid(suid);
        String wrongSuid = "wrong_suid";

        // When
        boolean result = UidEncoder.verifyCid(encoded, wrongSuid);

        // Then
        assertFalse(result, "使用错误的原始值应该验证失败");
    }

    @Test
    @DisplayName("测试verifyCid验证失败 - 篡改的编码")
    void testVerifyCid_tamperedEncoding() {
        // Given
        String suid = "original_suid";
        String encoded = UidEncoder.encodeCid(suid);
        String tamperedEncoded = "XXXX" + encoded.substring(4); // 篡改盐部分

        // When
        boolean result = UidEncoder.verifyCid(tamperedEncoded, suid);

        // Then
        assertFalse(result, "篡改的编码应该验证失败");
    }

    @Test
    @DisplayName("测试verifyCid验证失败 - null输入")
    void testVerifyCid_nullInputs() {
        // When & Then
        assertFalse(UidEncoder.verifyCid(null, "suid"), "null编码应该验证失败");
        assertFalse(UidEncoder.verifyCid("encoded", null), "null原始值应该验证失败");
        assertFalse(UidEncoder.verifyCid(null, null), "两个都为null应该验证失败");
    }

    @Test
    @DisplayName("测试verifyCid验证失败 - 长度不正确")
    void testVerifyCid_wrongLength() {
        // When & Then
        assertFalse(UidEncoder.verifyCid("short", "suid"), "长度太短应该验证失败");
        assertFalse(UidEncoder.verifyCid("verylongstringthatexceedslimit", "suid"),
                "长度太长应该验证失败");
    }

    @Test
    @DisplayName("测试verifyCid验证失败 - 非法字符")
    void testVerifyCid_illegalCharacters() {
        // Given
        String invalidEncoded = "12!@" + "AAAAAAAAAAAA"; // 包含数字和特殊字符的盐

        // When
        boolean result = UidEncoder.verifyCid(invalidEncoded, "suid");

        // Then
        assertFalse(result, "包含非法字符应该验证失败");
    }

    @Test
    @DisplayName("测试encodeCid和verifyCid的完整流程")
    void testEncodeCid_verifyCid_fullWorkflow() {
        // Given
        String[] suids = {"suid1", "suid2", "suid3"};

        for (String suid : suids) {
            // When
            String encoded = UidEncoder.encodeCid(suid);
            
            // Then
            assertTrue(UidEncoder.verifyCid(encoded, suid),
                    "应该能够验证编码: " + suid);
            
            // 验证其他suid不能通过
            for (String otherSuid : suids) {
                if (!otherSuid.equals(suid)) {
                    assertFalse(UidEncoder.verifyCid(encoded, otherSuid),
                            "不应该能够用其他suid验证: " + otherSuid);
                }
            }
        }
    }

    @Test
    @DisplayName("测试encodeUid和encodeCid的输出格式")
    void testOutputFormat() {
        // Given
        String uid = "format_test_uid";
        String suid = "format_test_suid";

        // When
        String uidEncoded = UidEncoder.encodeUid(uid);
        String cidEncoded = UidEncoder.encodeCid(suid);

        // Then
        // encodeUid输出固定12位
        assertEquals(12, uidEncoded.length(), "UID编码长度应为12");
        assertTrue(uidEncoded.chars().allMatch(c -> Character.isLetter(c)),
                "UID编码应该只包含字母");

        // encodeCid输出16位（4位盐+12位编码）
        assertEquals(16, cidEncoded.length(), "CID编码长度应为16");
        assertTrue(cidEncoded.chars().allMatch(c -> Character.isLetter(c)),
                "CID编码应该只包含字母");
    }

    @Test
    @DisplayName("测试encodeUid的碰撞率")
    void testEncodeUid_collisionRate() {
        // Given
        Set<String> encodings = new HashSet<>();
        int count = 10000;

        // When
        for (int i = 0; i < count; i++) {
            String uid = "user_" + i;
            encodings.add(UidEncoder.encodeUid(uid));
        }

        // Then
        double collisionRate = 1.0 - (double) encodings.size() / count;
        assertTrue(collisionRate < 0.01, 
                "碰撞率应该小于1%，实际: " + (collisionRate * 100) + "%");
    }

    @Test
    @DisplayName("测试encodeCid盐的随机性")
    void testEncodeCid_saltRandomness() {
        // Given
        String suid = "salt_test";
        Set<String> salts = new HashSet<>();
        int count = 1000;

        // When
        for (int i = 0; i < count; i++) {
            String encoded = UidEncoder.encodeCid(suid);
            String salt = encoded.substring(0, 4); // 提取盐部分
            salts.add(salt);
        }

        // Then
        // 盐的唯一性应该很高（接近100%）
        double uniqueRate = (double) salts.size() / count;
        assertTrue(uniqueRate > 0.95, 
                "盐的唯一率应该大于95%，实际: " + (uniqueRate * 100) + "%");
    }

    @Test
    @DisplayName("测试极长UID的编码")
    void testEncodeLongUid() {
        // Given
        String longUid = "a".repeat(1000);

        // When
        String encoded = UidEncoder.encodeUid(longUid);

        // Then
        assertEquals(12, encoded.length(), "即使UID很长，编码长度也应固定为12");
    }

    @Test
    @DisplayName("测试特殊字符UID的编码")
    void testEncodeSpecialCharacters() {
        // Given
        String[] specialUids = {
                "user@example.com",
                "user-123_456",
                "用户名123",
                "!@#$%^&*()"
        };

        // When & Then
        for (String uid : specialUids) {
            String encoded = UidEncoder.encodeUid(uid);
            assertNotNull(encoded, "特殊字符UID应该能够编码: " + uid);
            assertEquals(12, encoded.length(), "编码长度应该一致");
        }
    }
}
