package cn.flying.common.constant;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 分享类型枚举
 *
 * @author flyingcoding
 * @since 2025-12-23
 */
@Getter
@Schema(description = "分享类型枚举")
public enum ShareType {
    PUBLIC(0, "公开分享", "无需登录即可下载"),
    PRIVATE(1, "私密分享", "需要登录才能下载");

    @Schema(description = "类型代码")
    private final int code;

    @Schema(description = "类型名称")
    private final String name;

    @Schema(description = "类型描述")
    private final String description;

    ShareType(int code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    /**
     * 根据代码获取枚举
     * @param code 类型代码
     * @return 对应的枚举值，默认返回 PUBLIC
     */
    public static ShareType fromCode(Integer code) {
        if (code == null) {
            return PUBLIC;
        }
        for (ShareType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return PUBLIC;
    }

    /**
     * 判断是否为公开分享
     */
    public boolean isPublic() {
        return this == PUBLIC;
    }

    /**
     * 判断是否为私密分享
     */
    public boolean isPrivate() {
        return this == PRIVATE;
    }
}
