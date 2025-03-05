package cn.flying.common.util;

import cn.flying.common.exception.JsonParseException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TimeZone;

/**
 * Json 转换工具类
 */
public class JsonConverter {

    private static final Logger logger = LoggerFactory.getLogger(JsonConverter.class);
    private static final ObjectMapper om = new ObjectMapper();

    static{
        //忽略空值的序列化行为
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        //该属性设置主要是取消将对象的时间默认转换timesstamps(时间戳)形式
        om.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        //该属性设置主要是将忽略空bean转json错误
        om.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        //忽略反序列化未知的值
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        om.registerModule(new JavaTimeModule());
        om.setTimeZone(TimeZone.getTimeZone("GMT+8"));
    }

    /**
     * 反序列化
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象
     * @throws JsonParseException 如果解析失败
     */
    public static <T> T parse(String json, Class<T> clazz) throws JsonParseException {
        if (json == null || json.trim().isEmpty() || clazz == null) {
            throw new JsonParseException("JSON/类型不能为空");
        }

        try {
            return clazz == String.class ? (T) json : om.readValue(json, clazz);
        } catch (InvalidDefinitionException e) {
            throw new JsonParseException("无效的JSON定义", e);
        } catch (JsonProcessingException e) {
            throw new JsonParseException("JSON 解析失败", e);
        } catch (Exception e) {
            throw new JsonParseException("JSON 反序列化失败", e);
        }
    }


        /**
         * 序列化实例
         * @param bean
         * @param <T>
         * @return
         */
    public static <T> String toJson(T bean) {

        try {
            if (bean == null) {
                return null;
            }
            return bean instanceof String ? (String)bean : om.writeValueAsString(bean);
        } catch (Exception e) {
            logger.error("序列化失败", e);
        }
        return null;
    }

    /**
     * 格式化输出序列化实例之后的json
     * @param bean
     * @param <T>
     * @return
     */
    public static <T> String toJsonWithPretty(T bean){
        try {
            if (bean == null) {
                return null;
            }
            return bean instanceof String ? (String)bean : om.writerWithDefaultPrettyPrinter().writeValueAsString(bean);
        } catch (Exception e) {
            logger.error("序列化失败", e);
        }
        return null;
    }

    /**
     * 通过   TypeReference
     * @param json
     * @param typeReference
     * @param <T>
     * @return
     */
    public static <T> T parse(String json, TypeReference<T> typeReference) {
        if (CommonUtils.isEmpty(json) || typeReference == null) {
            return null;
        }

        try {
            return (typeReference.getType().equals(String.class) ? (T)json : om.readValue(json, typeReference));
        } catch (Exception e) {
            logger.error("反序列化失败", e);
            return null;
        }
    }

    /**
     * 通过javaType 来处理多泛型的转换
     * @param json
     * @param collectionClazz
     * @param elements
     * @param <T>
     * @return
     */
    public static <T> T parse(String json, Class<T> collectionClazz, Class<?>...elements) {
        JavaType javaType = om.getTypeFactory().constructParametricType(collectionClazz, elements);

        try {
            return om.readValue(json, javaType);
        } catch (Exception e) {
            logger.error("反序列化失败", e);
            return null;
        }
    }

}

