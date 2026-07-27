package cn.flying.service.key.rotation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies scheduler configuration cannot create a busy loop or unbounded tenant poll.
 */
class KeyRotationPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * Proves unsafe scheduler values violate their configuration binding contract.
     */
    @Test
    void shouldRejectUnsafeSchedulerBounds() {
        KeyRotationProperties properties = new KeyRotationProperties();
        properties.setPollIntervalMs(1L);
        properties.setInitialDelayMs(-1L);
        properties.setMaxTenantsPerPoll(10_001);

        assertThat(validator.validate(properties)).hasSize(3);
    }

    /**
     * Proves production defaults satisfy every scheduler bound.
     */
    @Test
    void shouldAcceptDefaults() {
        assertThat(validator.validate(new KeyRotationProperties())).isEmpty();
    }
}
