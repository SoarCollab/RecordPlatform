package cn.flying.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SensitiveDataMasker 单元测试
 */
class SensitiveDataMaskerTest {

    @Test
    @DisplayName("JSON字符串中的password字段应被脱敏")
    void maskSensitiveFields_shouldMaskPassword() {
        String json = "{\"username\":\"admin\",\"password\":\"secret123\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"username\":\"admin\""));
        assertTrue(masked.contains("\"password\":\"******\""));
        assertFalse(masked.contains("secret123"));
    }

    @Test
    @DisplayName("JSON字符串中的token字段应被脱敏")
    void maskSensitiveFields_shouldMaskToken() {
        String json = "{\"token\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\",\"userId\":\"123\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"userId\":\"123\""));
        assertTrue(masked.contains("\"token\":\"******\""));
        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
    }

    @Test
    @DisplayName("多个敏感字段应同时被脱敏")
    void maskSensitiveFields_shouldMaskMultipleFields() {
        String json = "{\"password\":\"pass1\",\"secret\":\"sec1\",\"apiKey\":\"key1\",\"name\":\"test\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"password\":\"******\""));
        assertTrue(masked.contains("\"secret\":\"******\""));
        assertTrue(masked.contains("\"apiKey\":\"******\""));
        assertTrue(masked.contains("\"name\":\"test\""));
    }

    @Test
    @DisplayName("嵌套JSON中的敏感字段应被脱敏")
    void maskSensitiveFields_shouldMaskNestedFields() {
        String json = "{\"user\":{\"name\":\"test\",\"credential\":\"cred123\"},\"token\":\"tok123\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"name\":\"test\""));
        assertTrue(masked.contains("\"credential\":\"******\""));
        assertTrue(masked.contains("\"token\":\"******\""));
    }

    @Test
    @DisplayName("空字符串应返回空字符串")
    void maskSensitiveFields_shouldHandleEmptyString() {
        assertEquals("", SensitiveDataMasker.maskSensitiveFields(""));
    }

    @Test
    @DisplayName("null应返回null")
    void maskSensitiveFields_shouldHandleNull() {
        assertNull(SensitiveDataMasker.maskSensitiveFields((String) null));
    }

    @Test
    @DisplayName("不区分大小写匹配敏感字段")
    void maskSensitiveFields_shouldBeCaseInsensitive() {
        String json = "{\"PASSWORD\":\"pass1\",\"Token\":\"tok1\",\"SECRET\":\"sec1\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"PASSWORD\":\"******\""));
        assertTrue(masked.contains("\"Token\":\"******\""));
        assertTrue(masked.contains("\"SECRET\":\"******\""));
    }

    @Test
    @DisplayName("Map中的敏感字段应被脱敏")
    void maskSensitiveFieldsMap_shouldMaskSensitiveKeys() {
        Map<String, Object> data = new HashMap<>();
        data.put("username", "admin");
        data.put("password", "secret123");
        data.put("token", "eyJhbGciOiJIUzI1NiJ9");

        Map<String, Object> masked = SensitiveDataMasker.maskSensitiveFields(data);

        assertEquals("admin", masked.get("username"));
        assertEquals("******", masked.get("password"));
        assertEquals("******", masked.get("token"));
    }

    @Test
    @DisplayName("嵌套Map中的敏感字段应被脱敏")
    void maskSensitiveFieldsMap_shouldMaskNestedMaps() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("apiKey", "key123");
        nested.put("name", "test");

        Map<String, Object> data = new HashMap<>();
        data.put("config", nested);
        data.put("secret", "sec123");

        Map<String, Object> masked = SensitiveDataMasker.maskSensitiveFields(data);

        assertEquals("******", masked.get("secret"));

        @SuppressWarnings("unchecked")
        Map<String, Object> maskedNested = (Map<String, Object>) masked.get("config");
        assertEquals("******", maskedNested.get("apiKey"));
        assertEquals("test", maskedNested.get("name"));
    }

    @Test
    @DisplayName("空Map应返回空Map")
    void maskSensitiveFieldsMap_shouldHandleEmptyMap() {
        Map<String, Object> result = SensitiveDataMasker.maskSensitiveFields(new HashMap<>());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("isSensitiveField应正确识别敏感字段")
    void isSensitiveField_shouldIdentifySensitiveFields() {
        assertTrue(SensitiveDataMasker.isSensitiveField("password"));
        assertTrue(SensitiveDataMasker.isSensitiveField("PASSWORD"));
        assertTrue(SensitiveDataMasker.isSensitiveField("token"));
        assertTrue(SensitiveDataMasker.isSensitiveField("secret"));
        assertTrue(SensitiveDataMasker.isSensitiveField("apiKey"));
        assertTrue(SensitiveDataMasker.isSensitiveField("credential"));

        assertFalse(SensitiveDataMasker.isSensitiveField("username"));
        assertFalse(SensitiveDataMasker.isSensitiveField("email"));
        assertFalse(SensitiveDataMasker.isSensitiveField("id"));
        assertFalse(SensitiveDataMasker.isSensitiveField(null));
        assertFalse(SensitiveDataMasker.isSensitiveField(""));
    }

    @Test
    @DisplayName("maskAndSerialize应正确序列化并脱敏对象")
    void maskAndSerialize_shouldSerializeAndMask() {
        TestUser user = new TestUser("admin", "secret123", "admin@test.com");
        String result = SensitiveDataMasker.maskAndSerialize(user);

        assertTrue(result.contains("\"username\":\"admin\""));
        assertTrue(result.contains("\"password\":\"******\""));
        assertTrue(result.contains("\"email\":\"admin@test.com\""));
        assertFalse(result.contains("secret123"));
    }

    @Test
    @DisplayName("maskAndSerialize处理null对象")
    void maskAndSerialize_shouldHandleNull() {
        assertNull(SensitiveDataMasker.maskAndSerialize((Object) null));
    }

    @Test
    @DisplayName("maskAndSerialize处理对象列表")
    void maskAndSerialize_shouldHandleList() {
        List<TestUser> users = List.of(
                new TestUser("user1", "pass1", "user1@test.com"),
                new TestUser("user2", "pass2", "user2@test.com")
        );

        String result = SensitiveDataMasker.maskAndSerialize(users);

        assertTrue(result.contains("\"username\":\"user1\""));
        assertTrue(result.contains("\"username\":\"user2\""));
        assertTrue(result.contains("\"password\":\"******\""));
        assertFalse(result.contains("pass1"));
        assertFalse(result.contains("pass2"));
    }

    @Test
    @DisplayName("JSON值包含转义引号应正确脱敏")
    void maskSensitiveFields_shouldHandleEscapedQuotes() {
        String json = "{\"password\":\"test\\\"123\",\"name\":\"test\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"password\":\"******\""));
        assertFalse(masked.contains("test\\\"123"));
        assertTrue(masked.contains("\"name\":\"test\""));
    }

    @Test
    @DisplayName("多个转义字符的JSON值应正确脱敏")
    void maskSensitiveFields_shouldHandleMultipleEscapes() {
        String json = "{\"token\":\"abc\\\"def\\\\ghi\",\"user\":\"admin\"}";
        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"token\":\"******\""));
        assertFalse(masked.contains("abc\\\"def\\\\ghi"));
        assertTrue(masked.contains("\"user\":\"admin\""));
    }

    /**
     * 测试用户类
     */
    static class TestUser {
        private String username;
        private String password;
        private String email;

        public TestUser(String username, String password, String email) {
            this.username = username;
            this.password = password;
            this.email = email;
        }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
