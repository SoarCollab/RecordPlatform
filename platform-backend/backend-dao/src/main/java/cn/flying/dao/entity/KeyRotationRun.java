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
 * Immutable policy snapshot and mutable lifecycle for one rotation execution.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("key_rotation_run")
public class KeyRotationRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long policyId;
    private Long policyVersion;
    private String triggerType;
    private String triggerKey;
    private String mode;
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
    private Integer maxAttempts;
    private Long initialBackoffSeconds;
    private Long maxBackoffSeconds;
    private Long leaseSeconds;
    private Long gracePeriodSeconds;
    private Long snapshotMaxEnvelopeId;
    private Long scanCursorId;
    private Integer discoveryComplete;
    private Long totalCount;
    private Long pendingCount;
    private Long runningCount;
    private Long succeededCount;
    private Long skippedCount;
    private Long failedCount;
    private Long remainingCount;
    private Date rateWindowStartedAt;
    private Integer rateWindowCount;
    private Long createdBy;
    private Date startedAt;
    private Date completedAt;
    private String retirementStatus;
    private Date retirementEligibleAt;
    private String lastErrorCategory;
    private String lastErrorClass;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
