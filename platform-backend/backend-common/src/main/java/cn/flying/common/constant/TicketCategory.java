package cn.flying.common.constant;

import lombok.Getter;

/**
 * 工单类别枚举
 */
@Getter
public enum TicketCategory {

    BUG(0, "Bug"),
    FEATURE_REQUEST(1, "功能请求"),
    QUESTION(2, "问题咨询"),
    FEEDBACK(3, "反馈建议"),
    OTHER(99, "其他");

    private final int code;
    private final String description;

    TicketCategory(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据类别码获取枚举
     */
    public static TicketCategory fromCode(int code) {
        for (TicketCategory category : values()) {
            if (category.code == code) {
                return category;
            }
        }
        return OTHER;
    }
}
