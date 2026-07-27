package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Claim-token fenced per-envelope rotation work item.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("key_rotation_item")
public class KeyRotationItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long runId;
    private Long sourceEnvelopeId;
    private Long candidateEnvelopeId;
    private Long fileId;
    private String recipientType;
    private Long recipientId;
    private String status;
    private String outcome;
    private Integer retryable;
    private Integer attemptCount;
    private String claimToken;
    private Date leaseExpiresAt;
    private Date nextRetryAt;
    private String failureCategory;
    private String lastErrorClass;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
