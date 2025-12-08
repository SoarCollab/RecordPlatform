package cn.flying.common.constant;

import lombok.Getter;

/**
 * 工单优先级枚举
 */
@Getter
public enum TicketPriority {

    LOW(0, "低"),
    MEDIUM(1, "中"),
    HIGH(2, "高");

    private final int code;
    private final String description;

    TicketPriority(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据优先级码获取枚举
     */
    public static TicketPriority fromCode(int code) {
        for (TicketPriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        return MEDIUM;
    }
}
