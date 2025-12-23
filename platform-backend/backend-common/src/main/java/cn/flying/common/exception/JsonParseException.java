package cn.flying.common.exception;

import cn.flying.common.constant.ResultEnum;
import lombok.extern.slf4j.Slf4j;

/**
 * @program: RecordPlatform
 * @description:
 * @author flyingcoding
 * @create: 2025-03-05 16:59
 */
@Slf4j
public class JsonParseException extends GeneralException {
    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, Throwable cause) {
        super(ResultEnum.JSON_PARSE_ERROR, message);
        log.error("JSON 解析失败", cause);
    }
}