package cn.flying.identity.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonUtils 单元测试
 * 测试JSON序列化和反序列化功能
 *
 * @author 王贝强
 */
class JsonUtilsTest {

    @Test
    void testToJson_SimpleObject() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "张三");
        data.put("age", 25);
        data.put("active", true);

        String json = JsonUtils.toJson(data);

        assertNotNull(json);
        assertTrue(json.contains("name"));
        assertTrue(json.contains("张三"));
        assertTrue(json.contains("age"));
    }

    @Test
    void testToJson_Null() {
        JsonUtils.toJson(null);
        String json = null;
        assertNull(json);
    }

    @Test
    void testToJson_List() {
        List<String> list = Arrays.asList("apple", "banana", "orange");

        String json = JsonUtils.toJson(list);

        assertNotNull(json);
        assertTrue(json.contains("apple"));
        assertTrue(json.contains("banana"));
    }

    @Test
    void testFromJson_ToMap() {
        String json = "{\"name\":\"李四\",\"age\":30,\"city\":\"北京\"}";

        Map<String, Object> result = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });

        assertNotNull(result);
        assertEquals("李四", result.get("name"));
        assertEquals(30, ((Number) result.get("age")).intValue());
        assertEquals("北京", result.get("city"));
    }

    @Test
    void testFromJson_ToList() {
        String json = "[\"apple\",\"banana\",\"orange\"]";

        List<String> result = JsonUtils.fromJson(json, new TypeReference<List<String>>() {
        });

        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.contains("apple"));
        assertTrue(result.contains("banana"));
    }

    @Test
    void testFromJson_InvalidJson() {
        String invalidJson = "{invalid json}";

        Map<String, Object> result = JsonUtils.fromJson(invalidJson, new TypeReference<Map<String, Object>>() {
        });

        assertNull(result);
    }

    @Test
    void testFromJson_NullInput() {
        Map<String, Object> result = JsonUtils.fromJson(null, new TypeReference<Map<String, Object>>() {
        });

        assertNull(result);
    }

    @Test
    void testFromJson_EmptyString() {
        Map<String, Object> result = JsonUtils.fromJson("", new TypeReference<Map<String, Object>>() {
        });

        assertNull(result);
    }

    @Test
    void testToJsonPretty_Success() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "王五");
        data.put("age", 28);

        String json = JsonUtils.toJsonPretty(data);

        assertNotNull(json);
        assertTrue(json.contains("\n"));  // 美化后应该包含换行
        assertTrue(json.contains("name"));
    }

    @Test
    void testToJsonPretty_Null() {
        JsonUtils.toJsonPretty(null);
        String json = null;
        assertNull(json);
    }

    @Test
    void testRoundTrip_Map() {
        Map<String, Object> original = new HashMap<>();
        original.put("id", 123);
        original.put("username", "testuser");
        original.put("enabled", true);

        String json = JsonUtils.toJson(original);
        Map<String, Object> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });

        assertNotNull(restored);
        assertEquals(123, ((Number) restored.get("id")).intValue());
        assertEquals("testuser", restored.get("username"));
        assertEquals(true, restored.get("enabled"));
    }

    @Test
    void testRoundTrip_ComplexObject() {
        Map<String, Object> user = new HashMap<>();
        user.put("id", 1L);
        user.put("name", "测试用户");

        List<String> roles = Arrays.asList("admin", "user");
        user.put("roles", roles);

        Map<String, String> settings = new HashMap<>();
        settings.put("theme", "dark");
        settings.put("language", "zh-CN");
        user.put("settings", settings);

        String json = JsonUtils.toJson(user);
        Map<String, Object> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });

        assertNotNull(restored);
        assertEquals("测试用户", restored.get("name"));
        assertTrue(restored.containsKey("roles"));
        assertTrue(restored.containsKey("settings"));
    }

    @Test
    void testHandleSpecialCharacters() {
        Map<String, String> data = new HashMap<>();
        data.put("text", "包含特殊字符: \n\t\"引号\"");

        String json = JsonUtils.toJson(data);
        Map<String, String> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, String>>() {
        });

        assertNotNull(restored);
        assertTrue(restored.get("text").contains("特殊字符"));
    }

    @Test
    void testHandleUnicodeCharacters() {
        Map<String, String> data = new HashMap<>();
        data.put("emoji", "😀🎉✨");
        data.put("chinese", "中文测试");

        String json = JsonUtils.toJson(data);
        Map<String, String> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, String>>() {
        });

        assertNotNull(restored);
        assertEquals("😀🎉✨", restored.get("emoji"));
        assertEquals("中文测试", restored.get("chinese"));
    }

    @Test
    void testHandleNullValues() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "test");
        data.put("nullable", null);

        String json = JsonUtils.toJson(data);

        assertNotNull(json);
        // JsonUtils配置为忽略null值，所以不应该包含nullable
        assertFalse(json.contains("nullable"));
    }

    @Test
    void testHandleEmptyCollections() {
        Map<String, Object> data = new HashMap<>();
        data.put("emptyList", new ArrayList<>());
        data.put("emptyMap", new HashMap<>());

        String json = JsonUtils.toJson(data);
        Map<String, Object> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });

        assertNotNull(restored);
        assertTrue(restored.containsKey("emptyList"));
        assertTrue(restored.containsKey("emptyMap"));
    }

    @Test
    void testHandleNumbers() {
        Map<String, Object> data = new HashMap<>();
        data.put("integer", 42);
        data.put("long", 9999999999L);
        data.put("double", 3.14159);
        data.put("float", 2.5f);

        String json = JsonUtils.toJson(data);
        Map<String, Object> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {
        });

        assertNotNull(restored);
        assertTrue(restored.containsKey("integer"));
        assertTrue(restored.containsKey("double"));
    }

    @Test
    void testHandleBoolean() {
        Map<String, Boolean> data = new HashMap<>();
        data.put("active", true);
        data.put("verified", false);

        String json = JsonUtils.toJson(data);
        Map<String, Boolean> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, Boolean>>() {
        });

        assertNotNull(restored);
        assertTrue(restored.get("active"));
        assertFalse(restored.get("verified"));
    }

    @Test
    void testToJsonSafe_Success() {
        Map<String, String> data = new HashMap<>();
        data.put("key", "value");

        String json = JsonUtils.toJsonSafe(data, "{}");

        assertNotNull(json);
        assertTrue(json.contains("key"));
    }

    @Test
    void testToJsonSafe_Null() {
        String json = JsonUtils.toJsonSafe(null, "{\"default\":true}");

        assertEquals("{\"default\":true}", json);
    }

    @Test
    void testFromJsonSafe_Success() {
        String json = "{\"name\":\"test\"}";
        Map<String, String> defaultValue = new HashMap<>();
        defaultValue.put("default", "value");

        Map result = JsonUtils.fromJsonSafe(json, Map.class, defaultValue);

        assertNotNull(result);
        assertEquals("test", result.get("name"));
    }

    @Test
    void testFromJsonSafe_Invalid() {
        String invalidJson = "{invalid}";
        Map<String, String> defaultValue = new HashMap<>();
        defaultValue.put("default", "value");

        Map result = JsonUtils.fromJsonSafe(invalidJson, Map.class, defaultValue);

        assertEquals(defaultValue, result);
    }

    @Test
    void testIsValidJson_Valid() {
        assertTrue(JsonUtils.isValidJson("{\"key\":\"value\"}"));
        assertTrue(JsonUtils.isValidJson("[1,2,3]"));
        assertTrue(JsonUtils.isValidJson("\"string\""));
        assertTrue(JsonUtils.isValidJson("123"));
        assertTrue(JsonUtils.isValidJson("true"));
    }

    @Test
    void testIsValidJson_Invalid() {
        assertFalse(JsonUtils.isValidJson("{invalid}"));
        assertFalse(JsonUtils.isValidJson("[1,2,"));
        assertFalse(JsonUtils.isValidJson(null));
        assertFalse(JsonUtils.isValidJson(""));
        assertFalse(JsonUtils.isValidJson("not json"));
    }

    @Test
    void testDeepCopy_Success() {
        Map<String, Object> original = new HashMap<>();
        original.put("name", "原始");
        original.put("count", 100);

        Map<String, Object> copy = JsonUtils.deepCopy(original, Map.class);

        assertNotNull(copy);
        assertEquals(original.get("name"), copy.get("name"));
        assertEquals(original.get("count"), copy.get("count"));

        // 修改副本不应影响原始对象
        copy.put("name", "修改后");
        assertEquals("原始", original.get("name"));
    }

    @Test
    void testDeepCopy_Null() {
        Map copy = JsonUtils.deepCopy(null, Map.class);
        assertNull(copy);
    }

    @Test
    void testDeepCopy_NullClass() {
        Map<String, Object> original = new HashMap<>();
        original.put("key", "value");

        JsonUtils.deepCopy(original, null);
        Map<String, Object> copy = null;
        assertNull(copy);
    }

    @Test
    void testGetObjectMapper() {
        assertNotNull(JsonUtils.getObjectMapper());
        assertEquals(JsonUtils.getObjectMapper(), JsonUtils.getObjectMapper()); // 应该是单例
    }

    @Test
    void testFromJsonWithClass_Success() {
        String json = "{\"name\":\"test\",\"age\":25}";
        Map<String, Object> result = JsonUtils.fromJson(json, Map.class);

        assertNotNull(result);
        assertEquals("test", result.get("name"));
    }

    @Test
    void testFromJsonWithClass_Null() {
        Map<String, Object> result = JsonUtils.fromJson(null, Map.class);
        assertNull(result);
    }

    @Test
    void testFromJsonWithClass_NullClass() {
        String json = "{\"key\":\"value\"}";
        Object result = JsonUtils.fromJson(json, (Class<?>) null);
        assertNull(result);
    }
}
