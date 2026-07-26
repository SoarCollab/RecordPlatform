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
 * Immutable per-file evidence snapshot and mutable claim/terminal lifecycle row.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("manifest_backfill_item")
public class ManifestBackfillItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private Integer fileVersion;

    private Long ownerUserId;

    private String status;

    private String classification;

    private String reasonCode;

    private Integer retryable;

    private Integer legacyDownloadAllowed;

    private String evidenceDigest;

    private String evidencePayload;

    private Long manifestId;

    private String claimToken;

    private Date leaseExpiresAt;

    private Integer attemptCount;

    private Date nextRetryAt;

    private String lastErrorClass;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
