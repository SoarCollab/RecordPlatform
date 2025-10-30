package cn.flying.identity.service;

import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.util.CacheUtils;
import cn.flying.identity.util.CommonUtils;
import cn.flying.identity.util.JsonUtils;
import cn.flying.identity.util.WebContextUtils;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 服務層基礎工具，提供通用的參數校驗、緩存與日誌能力
 *
 * @author 王贝强
 */
@Slf4j
public abstract class BaseService {

    @Resource
    protected CacheUtils cacheUtils;

    /**
     * 建立業務異常
     */
    protected BusinessException businessException(ResultEnum errorEnum, String message) {
        return new BusinessException(errorEnum, message);
    }

    /**
     * 參數不得為空
     */
    protected <T> T requireNonNull(T value, String message) {
        return requireNonNull(value, ResultEnum.PARAM_IS_INVALID, message);
    }

    /**
     * 參數不得為空（自訂錯誤碼）
     */
    protected <T> T requireNonNull(T value, ResultEnum errorEnum, String message) {
        if (value == null) {
            throw businessException(errorEnum, message);
        }
        return value;
    }

    /**
     * 字串不得為空白
     */
    protected String requireNonBlank(String value, String message) {
        return requireNonBlank(value, ResultEnum.PARAM_IS_INVALID, message);
    }

    /**
     * 字串不得為空白（自訂錯誤碼）
     */
    protected String requireNonBlank(String value, ResultEnum errorEnum, String message) {
        if (CommonUtils.isBlank(value)) {
            throw businessException(errorEnum, message);
        }
        return value;
    }

    /**
     * 集合不得為空
     */
    protected <T extends Collection<?>> T requireNonEmpty(T collection, String message) {
        return requireNonEmpty(collection, ResultEnum.PARAM_IS_INVALID, message);
    }

    /**
     * 集合不得為空（自訂錯誤碼）
     */
    protected <T extends Collection<?>> T requireNonEmpty(T collection, ResultEnum errorEnum, String message) {
        if (CommonUtils.isEmpty(collection)) {
            throw businessException(errorEnum, message);
        }
        return collection;
    }

    /**
     * 條件判斷
     */
    protected void requireTrue(boolean condition, String message) {
        requireTrue(condition, ResultEnum.PARAM_IS_INVALID, message);
    }

    /**
     * 條件判斷（自訂錯誤碼）
     */
    protected void requireTrue(boolean condition, ResultEnum errorEnum, String message) {
        if (!condition) {
            throw businessException(errorEnum, message);
        }
    }

    /**
     * 自訂判斷
     */
    protected <T> T requireCondition(T value, Predicate<T> predicate, String message) {
        return requireCondition(value, predicate, ResultEnum.PARAM_IS_INVALID, message);
    }

    /**
     * 自訂判斷（自訂錯誤碼）
     */
    protected <T> T requireCondition(T value, Predicate<T> predicate, ResultEnum errorEnum, String message) {
        if (predicate == null || !predicate.test(value)) {
            throw businessException(errorEnum, message);
        }
        return value;
    }

    /**
     * 批次檢查
     */
    @SafeVarargs
    protected final void validateAll(Runnable... validations) {
        if (validations == null) {
            return;
        }
        for (Runnable validation : validations) {
            if (validation != null) {
                validation.run();
            }
        }
    }

    /**
     * 字串是否為空白
     */
    protected boolean isBlank(String str) {
        return CommonUtils.isBlank(str);
    }

    /**
     * 字串是否非空白
     */
    protected boolean isNotBlank(String str) {
        return CommonUtils.isNotBlank(str);
    }

    /**
     * 物件是否為空
     */
    protected boolean isEmpty(Object obj) {
        return CommonUtils.isEmpty(obj);
    }

    /**
     * 物件是否非空
     */
    protected boolean isNotEmpty(Object obj) {
        return CommonUtils.isNotEmpty(obj);
    }

    /**
     * 回傳值或預設值
     */
    protected <T> T getOrElse(T value, T defaultValue) {
        return CommonUtils.getOrElse(value, defaultValue);
    }

    /**
     * 產生數字驗證碼
     */
    protected String generateVerifyCode(int length) {
        return CommonUtils.genRandomNumbers(length);
    }

    /**
     * 格式化時間
     */
    protected String formatDateTime(LocalDateTime dateTime, String pattern) {
        return CommonUtils.formatDateTime(dateTime, pattern);
    }

    /**
     * 取得當前客戶端 IP
     */
    protected String getCurrentClientIp() {
        return WebContextUtils.getCurrentClientIp();
    }

    /**
     * 物件轉 JSON
     */
    protected String toJson(Object object) {
        return JsonUtils.toJson(object);
    }

    /**
     * JSON 轉物件
     */
    protected <T> T fromJson(String json, Class<T> clazz) {
        return JsonUtils.fromJson(json, clazz);
    }

    /**
     * JSON 格式校驗
     */
    protected boolean isValidJson(String json) {
        return JsonUtils.isValidJson(json);
    }

    /**
     * 從快取取得資料，若不存在則由供應器提供
     */
    protected String getFromCacheOrLoad(String key, Supplier<String> supplier, long timeoutSeconds) {
        return cacheUtils.getOrSet(key, supplier, timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 寫入快取
     */
    protected void setCache(String key, String value, long timeoutSeconds) {
        cacheUtils.set(key, value, timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 刪除快取
     */
    protected void deleteCache(String key) {
        cacheUtils.delete(key);
    }

    /**
     * 快取是否存在
     */
    protected boolean existsCache(String key) {
        return cacheUtils.exists(key);
    }

    /**
     * 記錄除錯日誌
     */
    protected void logDebug(String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    /**
     * 記錄資訊日誌
     */
    protected void logInfo(String message, Object... args) {
        log.info(message, args);
    }

    /**
     * 記錄警告日誌
     */
    protected void logWarn(String message, Object... args) {
        log.warn(message, args);
    }

    /**
     * 記錄錯誤日誌
     */
    protected void logError(String message, Object... args) {
        log.error(message, args);
    }
}
