package cn.flying.health;

import cn.flying.service.encryption.ChunkEncryptionStrategy;
import cn.flying.service.encryption.EncryptionStrategyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("EncryptionHealthIndicator Tests")
class EncryptionHealthIndicatorTest {

    @Nested
    @DisplayName("health()")
    class HealthMethodTests {

        @Test
        @DisplayName("should return UP with full encryption details when strategy is available")
        void shouldReturnUpWithFullDetails() {
            EncryptionStrategyFactory factory = mock(EncryptionStrategyFactory.class);
            ChunkEncryptionStrategy strategy = mock(ChunkEncryptionStrategy.class);

            when(factory.getStrategy()).thenReturn(strategy);
            when(factory.getSelectionReason()).thenReturn("explicitly configured");
            when(strategy.getAlgorithmName()).thenReturn("ChaCha20-Poly1305");
            when(strategy.getIvSize()).thenReturn(12);
            when(strategy.getTagBitLength()).thenReturn(128);

            EncryptionHealthIndicator indicator = new EncryptionHealthIndicator(factory);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("algorithm", "ChaCha20-Poly1305")
                    .containsEntry("selectionReason", "explicitly configured")
                    .containsEntry("ivSize", 12)
                    .containsEntry("tagBitLength", 128)
                    .containsEntry("likelyHasAesNi", EncryptionStrategyFactory.hasLikelyAesNiSupport());
        }

        @Test
        @DisplayName("should return DOWN when factory fails to provide strategy")
        void shouldReturnDownWhenFactoryFails() {
            EncryptionStrategyFactory factory = mock(EncryptionStrategyFactory.class);
            when(factory.getStrategy()).thenThrow(new IllegalStateException("factory failed"));

            EncryptionHealthIndicator indicator = new EncryptionHealthIndicator(factory);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("error");
            assertThat(String.valueOf(health.getDetails().get("error"))).contains("factory failed");
        }

        @Test
        @DisplayName("should return DOWN when strategy accessor throws")
        void shouldReturnDownWhenStrategyAccessorThrows() {
            EncryptionStrategyFactory factory = mock(EncryptionStrategyFactory.class);
            ChunkEncryptionStrategy strategy = mock(ChunkEncryptionStrategy.class);

            when(factory.getStrategy()).thenReturn(strategy);
            when(strategy.getAlgorithmName()).thenThrow(new IllegalArgumentException("invalid strategy"));

            EncryptionHealthIndicator indicator = new EncryptionHealthIndicator(factory);

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("error");
            assertThat(String.valueOf(health.getDetails().get("error"))).contains("invalid strategy");
        }
    }

    @Test
    @DisplayName("should keep component name contract for actuator health key")
    void shouldKeepComponentNameContract() {
        Component component = EncryptionHealthIndicator.class.getAnnotation(Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("encryptionHealthIndicator");
    }
}
