package cn.flying.common.util;

import cn.flying.common.constant.ErrorPayload;

/**
 * ErrorPayload 构造工厂。
 * <p>
 * 将错误响应数据结构收敛到统一入口，避免各模块手工拼装 Map 导致字段不一致。
 * </p>
 */
public final class ErrorPayloadFactory {

    private ErrorPayloadFactory() {
    }

    /**
     * 构造普通错误载荷。
     *
     * @param traceId 链路追踪 ID
     * @param detail  错误细节
     * @return 统一错误载荷
     */
    public static ErrorPayload of(String traceId, Object detail) {
        return new ErrorPayload(traceId, detail);
    }

    /**
     * 构造可重试错误载荷。
     *
     * @param traceId           链路追踪 ID
     * @param detail            错误细节
     * @param retryAfterSeconds 建议重试间隔（秒）
     * @return 统一错误载荷
     */
    public static ErrorPayload retryable(String traceId, Object detail, int retryAfterSeconds) {
        return new ErrorPayload(traceId, detail, retryAfterSeconds);
    }
}

