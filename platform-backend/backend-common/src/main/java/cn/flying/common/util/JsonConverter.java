package cn.flying.common.util;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.exc.InvalidDefinitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TimeZone;

/**
 * Json 转换工具类
 */
public class JsonConverter {

    private static final Logger logger = LoggerFactory.getLogger(JsonConverter.class);
    /**
     * Jackson 3 的 Mapper 不可变：所有配置都在 builder 阶段完成。
     * java.time 支持已内置，无需再注册 JavaTimeModule。
     */
    private static final JsonMapper om = JsonMapper.builder()
            // 忽略空值的序列化行为
            .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
            // 取消把时间默认转成 timestamps
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 忽略空 bean 转 JSON 的错误
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            // 忽略反序列化时未知字段
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultTimeZone(TimeZone.getTimeZone("GMT+8"))
            .build();

    /**
     * 反序列化
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <T> 泛型类型
     * @return 反序列化后的对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T parse(String json, Class<T> clazz) {
        if (json == null || json.trim().isEmpty() || clazz == null) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "JSON/类型不能为空");
        }

        try {
            return clazz == String.class ? (T) json : om.readValue(json, clazz);
        } catch (InvalidDefinitionException e) {
            logger.error("无效的JSON定义", e);
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "无效的JSON定义");
        } catch (JacksonException e) {
            logger.error("JSON 解析失败", e);
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "JSON 解析失败");
        } catch (Exception e) {
            logger.error("JSON 反序列化失败", e);
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "JSON 反序列化失败");
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
    @SuppressWarnings("unchecked")
    public static <T> T parse(String json, TypeReference<T> typeReference) {
        if (CommonUtils.isEmpty(json) || typeReference == null) {
            return null;
        }

        try {
            return (typeReference.getType().equals(String.class) ? (T) json : om.readValue(json, typeReference));
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
