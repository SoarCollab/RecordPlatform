package cn.flying.service.sse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE 事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SseEvent {

    /**
     * 事件类型
     */
    private String type;

    /**
     * 事件数据
     */
    private Object payload;

    /**
     * 时间戳
     */
    private long timestamp;

    /**
     * 创建事件
     */
    public static SseEvent of(SseEventType type, Object payload) {
        return new SseEvent(type.getType(), payload, System.currentTimeMillis());
    }

    /**
     * 创建心跳事件
     */
    public static SseEvent heartbeat() {
        return new SseEvent(SseEventType.HEARTBEAT.getType(), null, System.currentTimeMillis());
    }

    /**
     * 创建连接成功事件
     */
    public static SseEvent connected() {
        return new SseEvent(SseEventType.CONNECTED.getType(), "连接成功", System.currentTimeMillis());
    }
}
