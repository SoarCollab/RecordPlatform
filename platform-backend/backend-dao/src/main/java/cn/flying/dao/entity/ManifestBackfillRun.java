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
 * Persisted manifest backfill scan, dry-run, or apply execution.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("manifest_backfill_run")
public class ManifestBackfillRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long snapshotRunId;

    private String mode;

    private String status;

    private String snapshotVersion;

    private String snapshotDigest;

    private Long cursorFileId;

    private Long createdBy;

    private Long totalCount;

    private Long pendingCount;

    private Long backfilledCount;

    private Long reuploadCount;

    private Long unrecoverableCount;

    private Long ignoredCount;

    private Long failedCount;

    private String lastErrorClass;

    private Date startedAt;

    private Date completedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
