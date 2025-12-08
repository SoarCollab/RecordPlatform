package cn.flying.dao.vo.announcement;

import cn.flying.common.constant.AnnouncementStatus;
import cn.flying.common.constant.MessagePriority;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 公告展示 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "公告信息")
public class AnnouncementVO {

    @Schema(description = "公告ID")
    private String id;

    @Schema(description = "公告标题")
    private String title;

    @Schema(description = "公告内容")
    private String content;

    @Schema(description = "优先级")
    private Integer priority;

    @Schema(description = "优先级描述")
    private String priorityDesc;

    @Schema(description = "是否置顶")
    private Boolean pinned;

    @Schema(description = "发布时间")
    private Date publishTime;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "发布者ID")
    private String publisherId;

    @Schema(description = "发布者名称")
    private String publisherName;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "创建时间")
    private Date createTime;

    /**
     * 设置优先级并填充描述
     */
    public AnnouncementVO setPriorityWithDesc(Integer priority) {
        this.priority = priority;
        this.priorityDesc = MessagePriority.fromCode(priority).getDescription();
        return this;
    }

    /**
     * 设置状态并填充描述
     */
    public AnnouncementVO setStatusWithDesc(Integer status) {
        this.status = status;
        this.statusDesc = AnnouncementStatus.fromCode(status).getDescription();
        return this;
    }
}
