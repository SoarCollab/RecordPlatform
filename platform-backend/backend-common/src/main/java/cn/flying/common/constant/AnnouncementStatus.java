package cn.flying.common.constant;

import lombok.Getter;

/**
 * 公告状态枚举
 */
@Getter
public enum AnnouncementStatus {

    DRAFT(0, "草稿"),
    PUBLISHED(1, "已发布"),
    EXPIRED(2, "已过期");

    private final int code;
    private final String description;

    AnnouncementStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据状态码获取枚举
     */
    public static AnnouncementStatus fromCode(int code) {
        for (AnnouncementStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return DRAFT;
    }
}
