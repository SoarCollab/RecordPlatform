package cn.flying.health;

import cn.flying.service.encryption.ChunkEncryptionStrategy;
import cn.flying.service.encryption.EncryptionStrategyFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes current encryption strategy status through Actuator health.
 */
@Component("encryptionHealthIndicator")
@RequiredArgsConstructor
public class EncryptionHealthIndicator implements HealthIndicator {

    private final EncryptionStrategyFactory strategyFactory;

    /**
     * Builds encryption health details from the currently selected strategy.
     *
     * @return UP with encryption details or DOWN when strategy resolution fails
     */
    @Override
    public Health health() {
        try {
            ChunkEncryptionStrategy strategy = strategyFactory.getStrategy();

            return Health.up()
                    .withDetail("algorithm", strategy.getAlgorithmName())
                    .withDetail("selectionReason", strategyFactory.getSelectionReason())
                    .withDetail("ivSize", strategy.getIvSize())
                    .withDetail("tagBitLength", strategy.getTagBitLength())
                    .withDetail("likelyHasAesNi", EncryptionStrategyFactory.hasLikelyAesNiSupport())
                    .build();

        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", "Encryption strategy initialization failed")
                    .withException(e)
                    .build();
        }
    }
}
