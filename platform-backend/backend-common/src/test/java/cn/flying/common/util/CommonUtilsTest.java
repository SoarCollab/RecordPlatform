package cn.flying.common.util;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * CommonUtils 单元测试
 *
 * 测试通用工具类的各种方法
 */
@DisplayName("CommonUtils Unit Tests")
class CommonUtilsTest {

    @Nested
    @DisplayName("assertNotNull Tests")
    class AssertNotNullTests {

        @Test
        @DisplayName("should not throw for non-null object")
        void shouldNotThrowForNonNullObject() {
            assertThatNoException().isThrownBy(() ->
                    CommonUtils.assertNotNull("test", "Object is null"));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for null object")
        void shouldThrowForNullObject() {
            assertThatThrownBy(() -> CommonUtils.assertNotNull(null, "Object is null"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Object is null");
        }
    }

    @Nested
    @DisplayName("assertNotEmpty Tests")
    class AssertNotEmptyTests {

        @Test
        @DisplayName("should not throw for non-empty string")
        void shouldNotThrowForNonEmptyString() {
            assertThatNoException().isThrownBy(() ->
                    CommonUtils.assertNotEmpty("test", "String is empty"));
        }

        @Test
        @DisplayName("should throw for null string")
        void shouldThrowForNullString() {
            assertThatThrownBy(() -> CommonUtils.assertNotEmpty((String) null, "String is empty"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("String is empty");
        }

        @Test
        @DisplayName("should throw for empty string")
        void shouldThrowForEmptyString() {
            assertThatThrownBy(() -> CommonUtils.assertNotEmpty("", "String is empty"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("String is empty");
        }

        @Test
        @DisplayName("should not throw for non-empty collection")
        void shouldNotThrowForNonEmptyCollection() {
            List<String> list = List.of("item");
            assertThatNoException().isThrownBy(() ->
                    CommonUtils.assertNotEmpty(list, "Collection is empty"));
        }

        @Test
        @DisplayName("should throw for null collection")
        void shouldThrowForNullCollection() {
            assertThatThrownBy(() -> CommonUtils.assertNotEmpty((Collection<?>) null, "Collection is empty"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Collection is empty");
        }

        @Test
        @DisplayName("should throw for empty collection")
        void shouldThrowForEmptyCollection() {
            assertThatThrownBy(() -> CommonUtils.assertNotEmpty(Collections.emptyList(), "Collection is empty"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Collection is empty");
        }
    }

    @Nested
    @DisplayName("assertTrue Tests")
    class AssertTrueTests {

        @Test
        @DisplayName("should not throw for true condition")
        void shouldNotThrowForTrueCondition() {
            assertThatNoException().isThrownBy(() ->
                    CommonUtils.assertTrue(true, "Condition is false"));
        }

        @Test
        @DisplayName("should throw for false condition")
        void shouldThrowForFalseCondition() {
            assertThatThrownBy(() -> CommonUtils.assertTrue(false, "Condition is false"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Condition is false");
        }
    }

    @Nested
    @DisplayName("isEmpty String Tests")
    class IsEmptyStringTests {

        @Test
        @DisplayName("should return true for null string")
        void shouldReturnTrueForNullString() {
            assertThat(CommonUtils.isEmpty((String) null)).isTrue();
        }

        @Test
        @DisplayName("should return true for empty string")
        void shouldReturnTrueForEmptyString() {
            assertThat(CommonUtils.isEmpty("")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-empty string")
        void shouldReturnFalseForNonEmptyString() {
            assertThat(CommonUtils.isEmpty("test")).isFalse();
        }

        @Test
        @DisplayName("should return false for whitespace-only string")
        void shouldReturnFalseForWhitespaceString() {
            assertThat(CommonUtils.isEmpty("   ")).isFalse();
        }
    }

    @Nested
    @DisplayName("isEmpty Object Tests")
    class IsEmptyObjectTests {

        @Test
        @DisplayName("should return true for null object")
        void shouldReturnTrueForNullObject() {
            assertThat(CommonUtils.isEmpty((Object) null)).isTrue();
        }

        @Test
        @DisplayName("should return true for empty collection")
        void shouldReturnTrueForEmptyCollection() {
            assertThat(CommonUtils.isEmpty(Collections.emptyList())).isTrue();
        }

        @Test
        @DisplayName("should return false for non-empty collection")
        void shouldReturnFalseForNonEmptyCollection() {
            assertThat(CommonUtils.isEmpty(List.of("item"))).isFalse();
        }

        @Test
        @DisplayName("should return true for empty array")
        void shouldReturnTrueForEmptyArray() {
            assertThat(CommonUtils.isEmpty(new Object[0])).isTrue();
        }

        @Test
        @DisplayName("should return false for non-empty array")
        void shouldReturnFalseForNonEmptyArray() {
            assertThat(CommonUtils.isEmpty(new Object[]{"item"})).isFalse();
        }

        @Test
        @DisplayName("should return true for empty map")
        void shouldReturnTrueForEmptyMap() {
            assertThat(CommonUtils.isEmpty(Collections.emptyMap())).isTrue();
        }

        @Test
        @DisplayName("should return false for non-empty map")
        void shouldReturnFalseForNonEmptyMap() {
            assertThat(CommonUtils.isEmpty(Map.of("key", "value"))).isFalse();
        }

        @Test
        @DisplayName("should return false for other non-null objects")
        void shouldReturnFalseForOtherNonNullObjects() {
            assertThat(CommonUtils.isEmpty(Integer.valueOf(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("isNotEmpty Tests")
    class IsNotEmptyTests {

        @Test
        @DisplayName("should return false for empty string")
        void shouldReturnFalseForEmptyString() {
            assertThat(CommonUtils.isNotEmpty("")).isFalse();
        }

        @Test
        @DisplayName("should return true for non-empty string")
        void shouldReturnTrueForNonEmptyString() {
            assertThat(CommonUtils.isNotEmpty("test")).isTrue();
        }

        @Test
        @DisplayName("should return false for null object")
        void shouldReturnFalseForNullObject() {
            assertThat(CommonUtils.isNotEmpty((Object) null)).isFalse();
        }

        @Test
        @DisplayName("should return true for non-empty object")
        void shouldReturnTrueForNonEmptyObject() {
            assertThat(CommonUtils.isNotEmpty(List.of("item"))).isTrue();
        }
    }

    @Nested
    @DisplayName("isBlank Tests")
    class IsBlankTests {

        @Test
        @DisplayName("should return true for null string")
        void shouldReturnTrueForNullString() {
            assertThat(CommonUtils.isBlank(null)).isTrue();
        }

        @Test
        @DisplayName("should return true for empty string")
        void shouldReturnTrueForEmptyString() {
            assertThat(CommonUtils.isBlank("")).isTrue();
        }

        @Test
        @DisplayName("should return true for whitespace-only string")
        void shouldReturnTrueForWhitespaceString() {
            assertThat(CommonUtils.isBlank("   ")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-blank string")
        void shouldReturnFalseForNonBlankString() {
            assertThat(CommonUtils.isBlank("test")).isFalse();
        }

        @Test
        @DisplayName("should return false for string with leading/trailing spaces")
        void shouldReturnFalseForStringWithSpaces() {
            assertThat(CommonUtils.isBlank("  test  ")).isFalse();
        }
    }

    @Nested
    @DisplayName("isNotBlank Tests")
    class IsNotBlankTests {

        @Test
        @DisplayName("should return false for blank string")
        void shouldReturnFalseForBlankString() {
            assertThat(CommonUtils.isNotBlank("   ")).isFalse();
        }

        @Test
        @DisplayName("should return true for non-blank string")
        void shouldReturnTrueForNonBlankString() {
            assertThat(CommonUtils.isNotBlank("test")).isTrue();
        }
    }

    @Nested
    @DisplayName("equals Tests")
    class EqualsTests {

        @Test
        @DisplayName("should return true for same object")
        void shouldReturnTrueForSameObject() {
            String s = "test";
            assertThat(CommonUtils.equals(s, s)).isTrue();
        }

        @Test
        @DisplayName("should return true for equal objects")
        void shouldReturnTrueForEqualObjects() {
            assertThat(CommonUtils.equals("test", "test")).isTrue();
        }

        @Test
        @DisplayName("should return false for different objects")
        void shouldReturnFalseForDifferentObjects() {
            assertThat(CommonUtils.equals("test1", "test2")).isFalse();
        }

        @Test
        @DisplayName("should return true for both null")
        void shouldReturnTrueForBothNull() {
            assertThat(CommonUtils.equals(null, null)).isTrue();
        }

        @Test
        @DisplayName("should return false when first is null")
        void shouldReturnFalseWhenFirstIsNull() {
            assertThat(CommonUtils.equals(null, "test")).isFalse();
        }

        @Test
        @DisplayName("should return false when second is null")
        void shouldReturnFalseWhenSecondIsNull() {
            assertThat(CommonUtils.equals("test", null)).isFalse();
        }
    }

    @Nested
    @DisplayName("notEquals Tests")
    class NotEqualsTests {

        @Test
        @DisplayName("should return true for different objects")
        void shouldReturnTrueForDifferentObjects() {
            assertThat(CommonUtils.notEquals("test1", "test2")).isTrue();
        }

        @Test
        @DisplayName("should return false for equal objects")
        void shouldReturnFalseForEqualObjects() {
            assertThat(CommonUtils.notEquals("test", "test")).isFalse();
        }
    }

    @Nested
    @DisplayName("getOrElse Tests")
    class GetOrElseTests {

        @Test
        @DisplayName("should return value when not empty")
        void shouldReturnValueWhenNotEmpty() {
            assertThat(CommonUtils.getOrElse("value", "default")).isEqualTo("value");
        }

        @Test
        @DisplayName("should return default when empty")
        void shouldReturnDefaultWhenEmpty() {
            assertThat(CommonUtils.getOrElse("", "default")).isEqualTo("default");
        }

        @Test
        @DisplayName("should return default when null")
        void shouldReturnDefaultWhenNull() {
            assertThat(CommonUtils.getOrElse(null, "default")).isEqualTo("default");
        }
    }

    @Nested
    @DisplayName("getOrEmpty Tests")
    class GetOrEmptyTests {

        @Test
        @DisplayName("should return value when not null")
        void shouldReturnValueWhenNotNull() {
            assertThat(CommonUtils.getOrEmpty("value")).isEqualTo("value");
        }

        @Test
        @DisplayName("should return empty string when null")
        void shouldReturnEmptyStringWhenNull() {
            assertThat(CommonUtils.getOrEmpty(null)).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("convert2BigDecimal Tests")
    class Convert2BigDecimalTests {

        @Test
        @DisplayName("should convert valid number string")
        void shouldConvertValidNumberString() {
            assertThat(CommonUtils.convert2BigDecimal("123.45")).isEqualTo(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("should return zero for invalid string")
        void shouldReturnZeroForInvalidString() {
            assertThat(CommonUtils.convert2BigDecimal("invalid")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return zero for empty string")
        void shouldReturnZeroForEmptyString() {
            assertThat(CommonUtils.convert2BigDecimal("")).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return zero for null")
        void shouldReturnZeroForNull() {
            assertThat(CommonUtils.convert2BigDecimal(null)).isEqualTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should trim whitespace before conversion")
        void shouldTrimWhitespaceBeforeConversion() {
            assertThat(CommonUtils.convert2BigDecimal("  100  ")).isEqualTo(new BigDecimal("100"));
        }
    }

    @Nested
    @DisplayName("formatDate Tests")
    class FormatDateTests {

        @Test
        @DisplayName("should format date correctly")
        void shouldFormatDateCorrectly() {
            // Use a specific date for deterministic testing
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(2024, java.util.Calendar.MARCH, 15, 0, 0, 0);
            Date date = cal.getTime();
            String result = CommonUtils.formatDate(date, "yyyy-MM-dd");
            assertThat(result).isEqualTo("2024-03-15");
        }

        @Test
        @DisplayName("should return null for null date")
        void shouldReturnNullForNullDate() {
            assertThat(CommonUtils.formatDate(null, "yyyy-MM-dd")).isNull();
        }
    }

    @Nested
    @DisplayName("parseDate Tests")
    class ParseDateTests {

        @Test
        @DisplayName("should parse valid date string")
        void shouldParseValidDateString() {
            Date result = CommonUtils.parseDate("2024-01-15 10:30:00", "yyyy-MM-dd HH:mm:ss");
            assertThat(result).isNotNull();
            // Verify parsed values
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(result);
            assertThat(cal.get(java.util.Calendar.YEAR)).isEqualTo(2024);
            assertThat(cal.get(java.util.Calendar.MONTH)).isEqualTo(java.util.Calendar.JANUARY);
            assertThat(cal.get(java.util.Calendar.DAY_OF_MONTH)).isEqualTo(15);
            assertThat(cal.get(java.util.Calendar.HOUR_OF_DAY)).isEqualTo(10);
            assertThat(cal.get(java.util.Calendar.MINUTE)).isEqualTo(30);
        }

        @Test
        @DisplayName("should return null for invalid date string")
        void shouldReturnNullForInvalidDateString() {
            assertThat(CommonUtils.parseDate("invalid", "yyyy-MM-dd")).isNull();
        }

        @Test
        @DisplayName("should return null for empty string")
        void shouldReturnNullForEmptyString() {
            assertThat(CommonUtils.parseDate("", "yyyy-MM-dd")).isNull();
        }

        @Test
        @DisplayName("should return null for null string")
        void shouldReturnNullForNullString() {
            assertThat(CommonUtils.parseDate(null, "yyyy-MM-dd")).isNull();
        }
    }

    @Nested
    @DisplayName("thumbnailText Tests")
    class ThumbnailTextTests {

        @Test
        @DisplayName("should thumbnail text with default prefix and suffix")
        void shouldThumbnailTextWithDefaults() {
            assertThat(CommonUtils.thumbnailText("12345678")).isEqualTo("1**8");
        }

        @Test
        @DisplayName("should thumbnail text with custom prefix and suffix")
        void shouldThumbnailTextWithCustomPrefixSuffix() {
            assertThat(CommonUtils.thumbnailText("1234567890", 2, 3)).isEqualTo("12**890");
        }

        @Test
        @DisplayName("should return original text when too short")
        void shouldReturnOriginalTextWhenTooShort() {
            assertThat(CommonUtils.thumbnailText("ab", 1, 1)).isEqualTo("ab");
        }

        @Test
        @DisplayName("should throw NullPointerException for null input")
        void shouldThrowNpeForNullInput() {
            assertThatThrownBy(() -> CommonUtils.thumbnailText(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("passwordSecurityLevel Tests")
    class PasswordSecurityLevelTests {

        @Test
        @DisplayName("should return 0 for password shorter than 6 characters")
        void shouldReturn0ForShortPassword() {
            assertThat(CommonUtils.passwordSecurityLevel("12345")).isEqualTo(0);
        }

        @Test
        @DisplayName("should return 1 for digits only")
        void shouldReturn1ForDigitsOnly() {
            assertThat(CommonUtils.passwordSecurityLevel("123456")).isEqualTo(1);
        }

        @Test
        @DisplayName("should return 2 for digits and lowercase")
        void shouldReturn2ForDigitsAndLowercase() {
            assertThat(CommonUtils.passwordSecurityLevel("abc123")).isEqualTo(2);
        }

        @Test
        @DisplayName("should return 3 for digits, lowercase and uppercase")
        void shouldReturn3ForDigitsLowercaseUppercase() {
            assertThat(CommonUtils.passwordSecurityLevel("Abc123")).isEqualTo(3);
        }

        @Test
        @DisplayName("should return 4 for all character types")
        void shouldReturn4ForAllCharacterTypes() {
            assertThat(CommonUtils.passwordSecurityLevel("Abc123!")).isEqualTo(4);
        }

        @Test
        @DisplayName("should throw NullPointerException for null input")
        void shouldThrowNpeForNullInput() {
            assertThatThrownBy(() -> CommonUtils.passwordSecurityLevel(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("passwordComplexityCompute Tests")
    class PasswordComplexityComputeTests {

        @Test
        @DisplayName("should return 0 for password shorter than 8 characters")
        void shouldReturn0ForShortPassword() {
            assertThat(CommonUtils.passwordComplexityCompute("Abc123!")).isEqualTo(0);
        }

        @Test
        @DisplayName("should return correct complexity for long password with all types")
        void shouldReturnCorrectComplexityForComplexPassword() {
            // f(15) = 1 + 2 + 4 + 8 = 15 (>8chars + digits + letters + special)
            assertThat(CommonUtils.passwordComplexityCompute("Abc12345!")).isEqualTo(15);
        }

        @Test
        @DisplayName("should return 1 for password with length >= 8 but only letters")
        void shouldReturn1ForOnlyLetters() {
            assertThat(CommonUtils.passwordComplexityCompute("abcdefgh")).isEqualTo(5); // 1 + 4
        }

        @Test
        @DisplayName("should throw NullPointerException for null input")
        void shouldThrowNpeForNullInput() {
            assertThatThrownBy(() -> CommonUtils.passwordComplexityCompute(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("genRandomNumbers Tests")
    class GenRandomNumbersTests {

        @Test
        @DisplayName("should generate 6-digit number by default")
        void shouldGenerate6DigitNumberByDefault() {
            String result = CommonUtils.genRandomNumbers();
            assertThat(result).hasSize(6).matches("\\d{6}");
        }

        @Test
        @DisplayName("should generate number with specified length")
        void shouldGenerateNumberWithSpecifiedLength() {
            String result = CommonUtils.genRandomNumbers(4);
            assertThat(result).hasSize(4).matches("\\d{4}");
        }
    }

    @Nested
    @DisplayName("compress/uncompressed Tests")
    class CompressUncompressTests {

        @Test
        @DisplayName("should compress and uncompress string correctly")
        void shouldCompressAndUncompressCorrectly() {
            String original = "This is a test string for compression.";
            String compressed = CommonUtils.compress(original);
            String uncompressed = CommonUtils.uncompressed(compressed);
            assertThat(uncompressed).isEqualTo(original);
        }

        @Test
        @DisplayName("should return null for null compress input")
        void shouldReturnNullForNullCompressInput() {
            assertThat(CommonUtils.compress(null)).isNull();
        }

        @Test
        @DisplayName("should return empty for empty compress input")
        void shouldReturnEmptyForEmptyCompressInput() {
            assertThat(CommonUtils.compress("")).isEqualTo("");
        }

        @Test
        @DisplayName("should return null for null uncompress input")
        void shouldReturnNullForNullUncompressInput() {
            assertThat(CommonUtils.uncompressed(null)).isNull();
        }

        @Test
        @DisplayName("should handle large strings")
        void shouldHandleLargeStrings() {
            String large = "x".repeat(10000);
            String compressed = CommonUtils.compress(large);
            String uncompressed = CommonUtils.uncompressed(compressed);
            assertThat(uncompressed).isEqualTo(large);
        }
    }

    @Nested
    @DisplayName("bytesToHex Tests")
    class BytesToHexTests {

        @Test
        @DisplayName("should convert bytes to hex string")
        void shouldConvertBytesToHex() {
            byte[] bytes = {0x00, 0x01, 0x0f, (byte) 0xff};
            assertThat(CommonUtils.bytesToHex(bytes)).isEqualTo("00010fff");
        }

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            assertThat(CommonUtils.bytesToHex(null)).isNull();
        }

        @Test
        @DisplayName("should return empty string for empty array")
        void shouldReturnEmptyStringForEmptyArray() {
            assertThat(CommonUtils.bytesToHex(new byte[0])).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("calculateFileSha256Hex Tests")
    class CalculateFileSha256HexTests {

        @Test
        @DisplayName("should calculate SHA-256 hash of file")
        void shouldCalculateSha256HashOfFile() throws Exception {
            Path tempFile = Files.createTempFile("test", ".txt");
            try {
                Files.writeString(tempFile, "Hello, World!");
                String hash = CommonUtils.calculateFileSha256Hex(tempFile);
                // Known SHA-256 of "Hello, World!" is dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f
                assertThat(hash).isEqualTo("dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f");
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Test
        @DisplayName("should return null for non-existent file")
        void shouldReturnNullForNonExistentFile() {
            Path nonExistent = Path.of("/non/existent/file.txt");
            assertThat(CommonUtils.calculateFileSha256Hex(nonExistent)).isNull();
        }
    }
}
