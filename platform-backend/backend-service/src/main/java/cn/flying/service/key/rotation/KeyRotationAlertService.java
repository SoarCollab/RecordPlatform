package cn.flying.service.key.rotation;

import cn.flying.service.key.KeyWrappingFailureCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Emits sanitized operator alerts without tenant, key, envelope, or provider response material.
 */
@Slf4j
@Component
public class KeyRotationAlertService {

    /**
     * Emits a stable terminal item failure signal for log-based alerting.
     */
    public void terminalFailure(String failureCategory) {
        log.warn("Automated key rotation item exhausted retries: failureCategory={}",
                stableCategory(failureCategory));
    }

    /**
     * Restricts log evidence to the provider-neutral failure enum.
     */
    private String stableCategory(String failureCategory) {
        if (failureCategory == null) {
            return KeyWrappingFailureCategory.INTERNAL.name();
        }
        try {
            return KeyWrappingFailureCategory.valueOf(
                    failureCategory.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException unknownCategory) {
            return KeyWrappingFailureCategory.INTERNAL.name();
        }
    }
}
