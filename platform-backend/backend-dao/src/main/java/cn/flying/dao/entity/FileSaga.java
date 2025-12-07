package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * File upload saga state entity.
 * Tracks the state machine for file upload distributed transactions.
 * 支持多租户隔离 (tenantId)。
 */
@Setter
@Getter
@TableName("file_saga")
@Accessors(chain = true)
public class FileSaga implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private String requestId;

    private Long userId;

    private String fileName;

    private String currentStep;

    private String status;

    private String payload;

    private Integer retryCount;

    private String lastError;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    public FileSaga advanceTo(FileSagaStep step) {
        this.currentStep = step.name();
        return this;
    }

    public FileSaga markStatus(FileSagaStatus status) {
        this.status = status.name();
        return this;
    }

    public boolean reachedStep(FileSagaStep step) {
        FileSagaStep current = FileSagaStep.valueOf(this.currentStep);
        return current.ordinal() >= step.ordinal();
    }

    public FileSaga recordError(Exception e) {
        this.lastError = e.getMessage();
        this.retryCount = (this.retryCount == null ? 0 : this.retryCount) + 1;
        return this;
    }
}
