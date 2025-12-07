package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Processed message entity for consumer idempotency.
 */
@Setter
@Getter
@TableName("processed_message")
public class ProcessedMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String messageId;

    private String eventType;

    @TableField(fill = FieldFill.INSERT)
    private Date processedAt;
}
