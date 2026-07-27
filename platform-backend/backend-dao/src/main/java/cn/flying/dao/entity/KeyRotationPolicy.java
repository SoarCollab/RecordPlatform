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
 * Persisted tenant policy for automated file-key envelope rotation.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("key_rotation_policy")
public class KeyRotationPolicy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String status;
    private String targetProvider;
    private Integer targetProviderContract;
    private String targetKeyId;
    private String targetProviderKeyVersion;
    private String targetWrappingAlgorithm;
    private String targetContextSchema;
    private Integer targetLogicalKeyVersion;
    private Integer batchSize;
    private Integer maxItemsPerMinute;
    private Integer scheduleEnabled;
    private Long scheduleIntervalSeconds;
    private Date nextRunAt;
    private Integer maxAttempts;
    private Long initialBackoffSeconds;
    private Long maxBackoffSeconds;
    private Long leaseSeconds;
    private Long gracePeriodSeconds;
    private Long policyVersion;
    private Long createdBy;
    private Long updatedBy;
    private Long lastRunId;
    private String retirementStatus;
    private Date retirementEligibleAt;
    private Date retirementAcknowledgedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
