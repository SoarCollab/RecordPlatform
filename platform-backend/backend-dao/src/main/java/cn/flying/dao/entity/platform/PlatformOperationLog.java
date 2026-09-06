package cn.flying.dao.entity.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import java.time.LocalDateTime;

/** System-owned durable platform command state; never returned as an entity. */
@Getter
@Setter
@Accessors(chain = true)
public class PlatformOperationLog {
    private Long id;
    private Long tenantId;
    private Long actorId;
    private String idempotencyKey;
    private String requestHash;
    private String operation;
    private Long targetTenantId;
    private String resourceType;
    private Long resourceId;
    private String resourceKey;
    private String reason;
    private String beforeSummary;
    private String afterSummary;
    private String status;
    private String resultJson;
    private Integer errorCode;
    private String traceId;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
}
