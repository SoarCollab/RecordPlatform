package cn.flying.common.constant;

import lombok.Getter;

/**
 * 工单状态枚举
 */
@Getter
public enum TicketStatus {

    PENDING(0, "待处理"),
    PROCESSING(1, "处理中"),
    CONFIRMING(2, "待确认"),
    COMPLETED(3, "已完成"),
    CLOSED(4, "已关闭");

    private final int code;
    private final String description;

    TicketStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据状态码获取枚举
     */
    public static TicketStatus fromCode(int code) {
        for (TicketStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return PENDING;
    }

    /**
     * 检查状态流转是否合法
     */
    public boolean canTransitionTo(TicketStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING || target == CLOSED;
            case PROCESSING -> target == CONFIRMING || target == CLOSED;
            case CONFIRMING -> target == COMPLETED || target == PROCESSING;
            case COMPLETED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
