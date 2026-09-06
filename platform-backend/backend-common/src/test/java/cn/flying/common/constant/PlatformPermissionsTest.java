package cn.flying.common.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the capability registry and reserved namespace contract. */
class PlatformPermissionsTest {

    /** Capability presentation is deterministic and callers cannot change the authority registry. */
    @Test
    void registryHasStableOrderAndCannotBeMutated() {
        assertThat(PlatformPermissions.allCodes()).containsExactly(
                "platform:tenant:read", "platform:tenant:write", "platform:user:read", "platform:user:write",
                "platform:quota:read", "platform:quota:write", "platform:configuration:read",
                "platform:configuration:write", "platform:resource:read", "platform:audit:read",
                "platform:overview:read");
        assertThatThrownBy(() -> PlatformPermissions.allCodes().add("platform:new:write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Unknown platform capabilities remain reserved even when they are not currently granted. */
    @ParameterizedTest
    @ValueSource(strings = {"platform:tenant:read", "PLATFORM:future:write", " platform:new ", "\tPlatform:read\n"})
    void reservesCaseAndWhitespaceVariants(String code) {
        assertThat(PlatformPermissions.isReserved(code)).isTrue();
    }

    /** Ordinary permission namespaces are not accidentally hidden by a substring comparison. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "platform", "file:platform:read", "platforms:read"})
    void preservesOrdinaryPermissionNamespaces(String code) {
        assertThat(PlatformPermissions.isReserved(code)).isFalse();
    }
}
