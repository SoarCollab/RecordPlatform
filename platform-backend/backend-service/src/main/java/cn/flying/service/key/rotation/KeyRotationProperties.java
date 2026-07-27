package cn.flying.service.key.rotation;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded scheduler controls independent from each tenant's durable policy.
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "key.rotation")
public class KeyRotationProperties {

    private boolean enabled;

    @Min(1_000)
    @Max(3_600_000)
    private long pollIntervalMs = 30_000L;

    @Min(0)
    @Max(3_600_000)
    private long initialDelayMs = 30_000L;

    @Min(1)
    @Max(10_000)
    private int maxTenantsPerPoll = 100;
}
