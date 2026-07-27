package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * Sanitized per-envelope outcome without wrapped or plaintext key material.
 */
@Schema(description = "Automated key rotation item")
public record KeyRotationItemVO(
        String id,
        String runId,
        String fileId,
        String recipientType,
        String status,
        String outcome,
        boolean retryable,
        int attemptCount,
        String failureCategory,
        Date nextRetryAt,
        Date updateTime
) {
}
