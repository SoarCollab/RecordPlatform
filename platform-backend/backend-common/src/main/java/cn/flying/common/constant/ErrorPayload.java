package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 统一错误响应数据载体。
 * <p>
 * 用于承载 traceId、错误细节以及可重试语义，确保过滤器、鉴权入口与全局异常处理器输出结构一致。
 * </p>
 */
@Getter
@Setter
@Schema(description = "统一错误响应数据")
public class ErrorPayload implements Serializable {

    @Schema(description = "链路追踪ID")
    private String traceId;

    @Schema(description = "错误细节")
    private Object detail;

    @Schema(description = "是否可重试")
    private Boolean retryable;

    @Schema(description = "建议重试间隔（秒）")
    private Integer retryAfterSeconds;

    /**
     * 构造基础错误数据。
     *
     * @param traceId 链路追踪 ID
     * @param detail  错误细节
     */
    public ErrorPayload(String traceId, Object detail) {
        this.traceId = traceId;
        this.detail = detail;
    }

    /**
     * 构造可重试错误数据。
     *
     * @param traceId           链路追踪 ID
     * @param detail            错误细节
     * @param retryAfterSeconds 建议重试间隔（秒）
     */
    public ErrorPayload(String traceId, Object detail, Integer retryAfterSeconds) {
        this.traceId = traceId;
        this.detail = detail;
        this.retryable = true;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}

