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
    @DisplayName("验证码和文件解密密钥字段应被脱敏")
    void maskSensitiveFields_shouldMaskVerificationAndDecryptFields() {
        String json = """
                {"email":"user@test.com","code":"123456","verificationCode":"654321","new_password":"newPass123","initialKey":"file-key","decryptKey":"decrypt-key","encryptedDataKey":"wrapped-key","wrappingIv":"nonce-value","kmsKeyId":"kms-key","keyId":"vault-key","keyName":"vault-key-name","historicalKeyIds":["old-key-id"],"ciphertext":"vault:v1:secret","vaultToken":"vault-token","context":"derived-context","wrappingContext":"context","grantReference":"grant-secret","downloadSessionId":"download-session-secret","clientId":"upload-session-secret","nonce":"public-nonce"}
                """;

        String masked = SensitiveDataMasker.maskSensitiveFields(json);

        assertTrue(masked.contains("\"email\":\"user@test.com\""));
        assertTrue(masked.contains("\"code\":\"******\""));
        assertTrue(masked.contains("\"verificationCode\":\"******\""));
        assertTrue(masked.contains("\"new_password\":\"******\""));
        assertTrue(masked.contains("\"initialKey\":\"******\""));
        assertTrue(masked.contains("\"decryptKey\":\"******\""));
        assertTrue(masked.contains("\"encryptedDataKey\":\"******\""));
        assertTrue(masked.contains("\"wrappingIv\":\"******\""));
        assertTrue(masked.contains("\"kmsKeyId\":\"******\""));
        assertTrue(masked.contains("\"keyId\":\"******\""));
        assertTrue(masked.contains("\"keyName\":\"******\""));
        assertTrue(masked.contains("\"historicalKeyIds\":\"******\""));
        assertTrue(masked.contains("\"ciphertext\":\"******\""));
        assertTrue(masked.contains("\"vaultToken\":\"******\""));
        assertTrue(masked.contains("\"context\":\"******\""));
        assertTrue(masked.contains("\"wrappingContext\":\"******\""));
        assertTrue(masked.contains("\"grantReference\":\"******\""));
        assertTrue(masked.contains("\"downloadSessionId\":\"******\""));
        assertTrue(masked.contains("\"clientId\":\"******\""));
        assertTrue(masked.contains("\"nonce\":\"public-nonce\""));
        assertFalse(masked.contains("123456"));
        assertFalse(masked.contains("654321"));
        assertFalse(masked.contains("newPass123"));
        assertFalse(masked.contains("file-key"));
        assertFalse(masked.contains("decrypt-key"));
        assertFalse(masked.contains("wrapped-key"));
        assertFalse(masked.contains("nonce-value"));
        assertFalse(masked.contains("kms-key"));
        assertFalse(masked.contains("grant-secret"));
        assertFalse(masked.contains("download-session-secret"));
        assertFalse(masked.contains("upload-session-secret"));
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
    @DisplayName("日志路径中的分享码、文件哈希和交易哈希应被脱敏")
    void maskSensitivePathSegments_shouldMaskSensitiveRouteVariables() {
        assertEquals(
                "/api/v1/public/shares/***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/public/shares/ABC123/files/hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/shares/***/files/***/decrypt-info",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/share-private/files/file-hash/decrypt-info")
        );
        assertEquals(
                "/api/v1/files/hash/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/hash/hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/files/share/***/stats",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/share/SHARE123/stats")
        );
        assertEquals(
                "/api/v1/transactions/***",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/transactions/0xdeadbeef")
        );
        assertEquals(
                "/api/v1/upload-sessions/***/chunks/3",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/upload-sessions/client-1/chunks/3")
        );
        assertEquals(
                "/api/v1/upload-sessions/***/complete",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/upload-sessions/resume-client/complete")
        );
        assertEquals(
                "/api/v1/upload-sessions/***/progress?verbose=true",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/upload-sessions/client-secret/progress?verbose=true")
        );
    }

    /**
     * 验证脱敏字面量与 Spring 路由的逐段解码和矩阵参数语义保持一致。
     */
    @Test
    @DisplayName("编码或矩阵参数路径中的分享凭据应被脱敏")
    void maskSensitivePathSegments_shouldMaskEncodedAndMatrixParameterizedRoutes() {
        assertEquals(
                "/api/v1/public/sh%61res/***/f%69les/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/sh%61res/share-secret/f%69les/hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares;x=1/***/files;v=2/***/decrypt-info",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares;x=1/share-secret;probe=1/files;v=2/hash-secret;probe=2/decrypt-info")
        );
        assertEquals(
                "/api/v1/public/shares;bad=%ZZ/***",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares;bad=%ZZ/share-secret")
        );
        assertEquals(
                "/api/v1/public/sh%61res;bad=%ZZ/***/f%69les;bad=%ZZ/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/sh%61res;bad=%ZZ/share-secret/f%69les;bad=%ZZ/file-hash-secret/chunks")
        );
    }

    /**
     * 验证容器折叠空段、点段和父段后，实际参与路由的分享凭据仍会被脱敏。
     */
    @Test
    @DisplayName("容器规范化路径中的实际分享凭据应被脱敏")
    void maskSensitivePathSegments_shouldMaskContainerNormalizedRoutes() {
        assertEquals(
                "/api/v1/public/shares/./***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares/./share-secret/files/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares//***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares//share-secret/files/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/%2E/***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares/%2E/share-secret/files/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/***/../***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares/decoy-secret/../share-secret/files/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/***/%2e%2E/***/files/***/chunks",
                SensitiveDataMasker.maskSensitivePathSegments(
                        "/api/v1/public/shares/encoded-decoy/%2e%2E/share-secret/files/file-hash-secret/chunks")
        );
    }

    /**
     * 验证路由分类使用的规范路径会逐段解码、移除矩阵参数并忽略非路径后缀。
     */
    @Test
    @DisplayName("路由匹配路径应遵循 Spring 的逐段解码语义")
    void normalizePathForRouteMatching_shouldDecodeSegmentsAndRemoveMatrixParameters() {
        assertEquals(
                "/api/v1/public/shares/share-secret/files/hash-secret/chunks",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api/v1/public/sh%61res;x=1/share-secret/f%69les;v=2/hash-secret/chunks?download=true")
        );
        assertEquals(
                "/api/v1/public/sh/ares/share-secret",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api/v1/public/sh%2Fares/share-secret")
        );
        assertEquals(
                "/api/v1/public/shares/share-secret/files/file-hash-secret/chunks",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api/v1/public/sh%61res;bad=%ZZ/share-secret/f%69les;bad=%ZZ/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/share-secret/files/file-hash-secret/chunks",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api//v1/public/shares/%2E/decoy-secret/../share-secret/files//file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/share-secret/files/file-hash-secret/chunks",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api/v1/public/shares/encoded-decoy/%2e%2E/share-secret/files/file-hash-secret/chunks")
        );
        assertEquals(
                "/api/v1/public/shares/share-secret/",
                SensitiveDataMasker.normalizePathForRouteMatching(
                        "/api/v1/public/shares/share-secret/.")
        );
    }

    /**
     * 验证路由规范化对空输入、相对路径和容器尾部分隔符语义保持稳定。
     */
    @Test
    @DisplayName("路由匹配路径应稳定处理空输入和尾部分隔符")
    void normalizePathForRouteMatching_shouldHandleEmptyAndTrailingSegments() {
        assertNull(SensitiveDataMasker.normalizePathForRouteMatching(null));
        assertEquals("   ", SensitiveDataMasker.normalizePathForRouteMatching("   "));
        assertEquals("api/v1/shares", SensitiveDataMasker.normalizePathForRouteMatching("api/v1/shares"));
        assertEquals("", SensitiveDataMasker.normalizePathForRouteMatching("."));
        assertEquals("/", SensitiveDataMasker.normalizePathForRouteMatching("/"));
        assertEquals("/api/", SensitiveDataMasker.normalizePathForRouteMatching("/api/"));
        assertEquals("/", SensitiveDataMasker.normalizePathForRouteMatching("/api/.."));
        assertEquals("/api", SensitiveDataMasker.normalizePathForRouteMatching("/../api"));
        assertEquals("/api", SensitiveDataMasker.normalizePathForRouteMatching("/api/;x=1"));
        assertEquals("/", SensitiveDataMasker.normalizePathForRouteMatching("/;x=1"));
        assertEquals("/api", SensitiveDataMasker.normalizePathForRouteMatching("/api"));
    }

    /**
     * 验证终止路由、导航段和非法编码均不会绕过或误触发敏感路径变量脱敏。
     */
    @Test
    @DisplayName("异常路由目标应按实际路径变量语义脱敏")
    void maskSensitivePathSegments_shouldHandleTerminalNavigationAndInvalidTargets() {
        assertEquals(
                "/api/v1/upload-sessions",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/upload-sessions")
        );
        assertEquals(
                "/api/v1/hash",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/hash")
        );
        assertEquals(
                "/api/v1/files",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files")
        );
        assertEquals(
                "/api/v1/shares/../public",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/../public")
        );
        assertEquals(
                "/api/v1/shares/***",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/%ZZ")
        );
        assertEquals(
                "/api/v1/shares/***",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/%ZZ;bad=%ZZ")
        );
    }

    @Test
    @DisplayName("日志路径脱敏不应误替换静态文件路由段")
    void maskSensitivePathSegments_shouldKeepStaticFileRouteSegments() {
        assertEquals(
                "/api/v1/files/stats",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/stats")
        );
        assertEquals(
                "/api/v1/files/download-batches/report",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/download-batches/report")
        );
        assertEquals(
                "/api/v1/shares/***/files/save",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/ABC123/files/save")
        );
        assertEquals(
                "/api/v1/f%69les;x=1/st%61ts;view=summary",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/f%69les;x=1/st%61ts;view=summary")
        );
    }

    /**
     * 验证空路径输入会保持原值返回，避免日志过滤器处理异常请求路径时报错。
     */
    @Test
    @DisplayName("空路径应保持原样")
    void maskSensitivePathSegments_shouldKeepBlankPathInputs() {
        assertNull(SensitiveDataMasker.maskSensitivePathSegments(null));
        assertEquals("   ", SensitiveDataMasker.maskSensitivePathSegments("   "));
    }

    /**
     * 验证路径脱敏只处理路径段本身，并保留查询参数或 fragment 后缀。
     */
    @Test
    @DisplayName("路径脱敏应保留查询参数和fragment后缀")
    void maskSensitivePathSegments_shouldPreserveSuffixes() {
        assertEquals(
                "/api/v1/shares/***#details",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/share-secret#details")
        );
        assertEquals(
                "/api/v1/files/hash/***?download=true#section",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/hash/file-hash?download=true#section")
        );
    }

    /**
     * 验证已经脱敏或缺少变量值的路径不会被重复替换。
     */
    @Test
    @DisplayName("已脱敏或缺少变量值的路径应保持稳定")
    void maskSensitivePathSegments_shouldKeepAlreadyMaskedOrTerminalRoutes() {
        assertEquals(
                "/api/v1/shares/***",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/shares/***")
        );
        assertEquals(
                "/api/v1/share",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/share")
        );
        assertEquals(
                "/api/v1/files/hash/***",
                SensitiveDataMasker.maskSensitivePathSegments("/api/v1/files/hash/***")
        );
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
        assertTrue(SensitiveDataMasker.isSensitiveField("code"));
        assertTrue(SensitiveDataMasker.isSensitiveField("verificationCode"));
        assertTrue(SensitiveDataMasker.isSensitiveField("new_password"));
        assertTrue(SensitiveDataMasker.isSensitiveField("initialKey"));
        assertTrue(SensitiveDataMasker.isSensitiveField("encryptedDataKey"));
        assertTrue(SensitiveDataMasker.isSensitiveField("wrapped_data_key"));
        assertTrue(SensitiveDataMasker.isSensitiveField("kmsKeyId"));
        assertTrue(SensitiveDataMasker.isSensitiveField("grantReference"));
        assertTrue(SensitiveDataMasker.isSensitiveField("grant_reference"));
        assertTrue(SensitiveDataMasker.isSensitiveField("downloadSessionId"));
        assertTrue(SensitiveDataMasker.isSensitiveField("session_id"));
        assertTrue(SensitiveDataMasker.isSensitiveField("clientId"));
        assertTrue(SensitiveDataMasker.isSensitiveField("client_id"));

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
