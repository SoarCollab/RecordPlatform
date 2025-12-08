package cn.flying.service.sse;

import lombok.Getter;

/**
 * SSE 事件类型枚举
 */
@Getter
public enum SseEventType {

    /**
     * 新私信
     */
    NEW_MESSAGE("NEW_MESSAGE", "新私信"),

    /**
     * 新公告
     */
    NEW_ANNOUNCEMENT("NEW_ANNOUNCEMENT", "新公告"),

    /**
     * 工单更新
     */
    TICKET_UPDATE("TICKET_UPDATE", "工单状态更新"),

    /**
     * 工单回复
     */
    TICKET_REPLY("TICKET_REPLY", "工单回复"),

    /**
     * 心跳
     */
    HEARTBEAT("HEARTBEAT", "心跳"),

    /**
     * 连接成功
     */
    CONNECTED("CONNECTED", "连接成功");

    private final String type;
    private final String description;

    SseEventType(String type, String description) {
        this.type = type;
        this.description = description;
    }
}
