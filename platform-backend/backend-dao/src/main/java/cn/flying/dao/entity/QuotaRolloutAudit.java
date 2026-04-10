package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 配额灰度扩容审计实体。
 * 用于记录每个批次在租户维度的观察结果与回滚决策。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("quota_rollout_audit")
public class QuotaRolloutAudit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String batchId;

    private Long tenantId;

    private Date observationStartTime;

    private Date observationEndTime;

    private Long sampledRequestCount;

    private Long exceededRequestCount;

    private Long falsePositiveCount;

    private String rollbackDecision;

    private String rollbackReason;

    private String evidenceLink;

    private String operatorName;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}
