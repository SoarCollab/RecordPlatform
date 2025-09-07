package cn.flying.identity.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.TimeZone;

/**
 * JSON 转换工具类
 * 提供对象与JSON字符串之间的序列化和反序列化功能
 * 
 * @author 王贝强
 */
@Slf4j
public class JsonUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        // 忽略空值的序列化行为
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // 取消将对象的时间默认转换为timestamps(时间戳)形式
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        // 忽略空bean转json错误
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // 忽略反序列化未知的值
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 注册Java时间模块
        objectMapper.registerModule(new JavaTimeModule());
        // 设置时区
        objectMapper.setTimeZone(TimeZone.getTimeZone("GMT+8"));
    }

    private JsonUtils() {}

    /**
     * 对象序列化为JSON字符串
     * 
     * @param object 要序列化的对象
     * @return JSON字符串，失败时返回null
     */
    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return object instanceof String ? (String) object : objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("对象序列化为JSON失败", e);
            return null;
        }
    }

    /**
     * 对象序列化为格式化的JSON字符串
     * 
     * @param object 要序列化的对象
     * @return 格式化的JSON字符串，失败时返回null
     */
    public static String toJsonPretty(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return object instanceof String ? (String) object : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("对象序列化为格式化JSON失败", e);
            return null;
        }
    }

    /**
     * JSON字符串反序列化为对象
     * 
     * @param json JSON字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象，失败时返回null
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, Class<T> clazz) {
        if (CommonUtils.isEmpty(json) || clazz == null) {
            return null;
        }
        try {
            return clazz == String.class ? (T) json : objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * 通过TypeReference反序列化复杂类型
     * 
     * @param json JSON字符串
     * @param typeReference 类型引用
     * @param <T> 泛型类型
     * @return 反序列化后的对象，失败时返回null
     */
    @SuppressWarnings("unchecked")
    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (CommonUtils.isEmpty(json) || typeReference == null) {
            return null;
        }
        try {
            return (typeReference.getType().equals(String.class) ? (T) json : objectMapper.readValue(json, typeReference));
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * 通过JavaType来处理多泛型的转换
     * 
     * @param json JSON字符串
     * @param collectionClazz 集合类型
     * @param elementClasses 元素类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象，失败时返回null
     */
    public static <T> T fromJson(String json, Class<T> collectionClazz, Class<?>... elementClasses) {
        if (CommonUtils.isEmpty(json) || collectionClazz == null) {
            return null;
        }
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructParametricType(collectionClazz, elementClasses);
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            log.error("JSON反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * 安全的JSON序列化，失败时返回默认值
     * 
     * @param object 要序列化的对象
     * @param defaultValue 默认值
     * @return JSON字符串或默认值
     */
    public static String toJsonSafe(Object object, String defaultValue) {
        String result = toJson(object);
        return result != null ? result : defaultValue;
    }

    /**
     * 安全的JSON反序列化，失败时返回默认值
     * 
     * @param json JSON字符串
     * @param clazz 目标类型
     * @param defaultValue 默认值
     * @param <T> 泛型类型
     * @return 反序列化后的对象或默认值
     */
    public static <T> T fromJsonSafe(String json, Class<T> clazz, T defaultValue) {
        T result = fromJson(json, clazz);
        return result != null ? result : defaultValue;
    }

    /**
     * 判断字符串是否为有效的JSON格式
     * 
     * @param json 待验证的字符串
     * @return 是否为有效JSON
     */
    public static boolean isValidJson(String json) {
        if (CommonUtils.isEmpty(json)) {
            return false;
        }
        try {
            objectMapper.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 对象深拷贝（通过JSON序列化实现）
     * 
     * @param object 源对象
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 深拷贝后的对象，失败时返回null
     */
    public static <T> T deepCopy(Object object, Class<T> clazz) {
        if (object == null || clazz == null) {
            return null;
        }
        String json = toJson(object);
        return fromJson(json, clazz);
    }

    /**
     * 获取ObjectMapper实例（用于特殊场景）
     * 
     * @return ObjectMapper实例
     */
    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
