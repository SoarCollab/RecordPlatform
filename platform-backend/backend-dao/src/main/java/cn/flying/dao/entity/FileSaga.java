package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Date;

/**
 * 文件上传 Saga 状态实体。
 * 跟踪文件上传分布式事务的状态机。
 * 支持多租户隔离 (tenantId) 和指数退避重试。
 */
@Setter
@Getter
@TableName("file_saga")
@Accessors(chain = true)
public class FileSaga implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 指数退避重试间隔（秒）：5s, 30s, 2min, 10min, 1h
     */
    private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600, 3600};

    @TableId(type = IdType.ASSIGN_ID)
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

    /**
     * 下次重试时间，用于指数退避调度
     */
    private Date nextRetryAt;

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

    /**
     * 计算并设置下次重试时间（指数退避）
     */
    public FileSaga scheduleNextRetry() {
        int count = this.retryCount == null ? 0 : this.retryCount;
        int index = Math.min(count, BACKOFF_SECONDS.length - 1);
        int delaySeconds = BACKOFF_SECONDS[index];
        this.nextRetryAt = Date.from(Instant.now().plusSeconds(delaySeconds));
        return this;
    }

    /**
     * 检查是否已超过最大重试次数
     */
    public boolean isMaxRetriesExceeded(int maxRetries) {
        return this.retryCount != null && this.retryCount >= maxRetries;
    }

    /**
     * 检查是否可以进行重试（当前时间已过 nextRetryAt）
     */
    public boolean isRetryDue() {
        if (this.nextRetryAt == null) {
            return true;
        }
        return Instant.now().isAfter(this.nextRetryAt.toInstant());
    }
}
