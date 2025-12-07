package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Outbox event entity for reliable messaging pattern.
 * 支持分布式追踪 (traceId) 和多租户 (tenantId)。
 */
@Setter
@Getter
@TableName("outbox_event")
@Accessors(chain = true)
public class OutboxEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String traceId;

    private String aggregateType;

    private Long aggregateId;

    private String eventType;

    private String payload;

    private String status;

    private Date nextAttemptAt;

    private Integer retryCount;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    private Date sentTime;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
}
