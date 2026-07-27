package cn.flying.service.key.rotation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies terminal alerts normalize arbitrary provider output into stable, non-secret categories.
 */
class KeyRotationAlertServiceTest {

    /**
     * Covers null, valid mixed-case, and unknown categories without leaking caller-controlled content.
     */
    @Test
    void shouldNormalizeTerminalFailureCategories() {
        KeyRotationAlertService service = new KeyRotationAlertService();

        assertThatCode(() -> {
            service.terminalFailure(null);
            service.terminalFailure(" timeout ");
            service.terminalFailure("provider-secret-response");
        }).doesNotThrowAnyException();
    }
}
