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
    NEW_MESSAGE("message-received", "新私信"),

    /**
     * 新公告
     */
    NEW_ANNOUNCEMENT("announcement-published", "新公告"),

    /**
     * 工单更新
     */
    TICKET_UPDATE("ticket-updated", "工单状态更新"),

    /**
     * 工单回复
     */
    TICKET_REPLY("ticket-updated", "工单回复"),

    /**
     * 新好友请求
     */
    FRIEND_REQUEST("friend-request", "新好友请求"),

    /**
     * 好友请求被接受
     */
    FRIEND_ACCEPTED("friend-accepted", "好友请求被接受"),

    /**
     * 好友文件分享
     */
    FRIEND_SHARE("friend-share", "好友文件分享"),

    /**
     * 文件存证成功
     */
    FILE_RECORD_SUCCESS("file-record-success", "文件存证成功"),

    /**
     * 文件存证失败
     */
    FILE_RECORD_FAILED("file-record-failed", "文件存证失败"),

    /**
     * 审计告警
     */
    AUDIT_ALERT("audit-alert", "审计告警"),

    /**
     * 心跳
     */
    HEARTBEAT("heartbeat", "心跳"),

    /**
     * 连接成功
     */
    CONNECTED("connected", "连接成功");

    private final String type;
    private final String description;

    SseEventType(String type, String description) {
        this.type = type;
        this.description = description;
    }
}
