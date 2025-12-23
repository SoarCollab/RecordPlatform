package cn.flying.service.encryption;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 加密策略健康检查指示器
 *
 * <p>通过 /actuator/health 端点展示当前加密配置状态。</p>
 *
 * <h3>健康检查输出示例：</h3>
 * <pre>
 * {
 *   "encryption": {
 *     "status": "UP",
 *     "details": {
 *       "algorithm": "ChaCha20-Poly1305",
 *       "selectionReason": "explicitly configured",
 *       "ivSize": 12,
 *       "tagBitLength": 128
 *     }
 *   }
 * }
 * </pre>
 *
 * @author flyingcoding
 * @since 2.0.0
 */
@Component("encryptionHealthIndicator")
@RequiredArgsConstructor
public class EncryptionHealthIndicator implements HealthIndicator {

    private final EncryptionStrategyFactory strategyFactory;

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
