package cn.flying.common.constant;

import lombok.Getter;

/**
 * 消息优先级枚举（用于公告）
 */
@Getter
public enum MessagePriority {

    NORMAL(0, "普通"),
    IMPORTANT(1, "重要"),
    URGENT(2, "紧急");

    private final int code;
    private final String description;

    MessagePriority(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据优先级码获取枚举
     */
    public static MessagePriority fromCode(int code) {
        for (MessagePriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        return NORMAL;
    }
}
