package cn.flying.api.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResultEnum Contract Tests")
class ResultEnumContractTest {

    /**
     * Verifies that every cross-service platform-api ResultEnum remains compatible with backend-common.
     */
    @Test
    @DisplayName("should keep platform-api result enums compatible with backend-common")
    void shouldKeepPlatformApiResultEnumsCompatibleWithBackendCommon() {
        Map<String, cn.flying.common.constant.ResultEnum> commonEnums = Arrays.stream(
                        cn.flying.common.constant.ResultEnum.values())
                .collect(Collectors.toMap(Enum::name, Function.identity()));

        for (cn.flying.platformapi.constant.ResultEnum apiEnum : cn.flying.platformapi.constant.ResultEnum.values()) {
            cn.flying.common.constant.ResultEnum commonEnum = commonEnums.get(apiEnum.name());

            assertThat(commonEnum)
                    .as("backend-common should contain shared enum %s", apiEnum.name())
                    .isNotNull();
            assertThat(commonEnum.getCode())
                    .as("shared enum %s should keep the same code", apiEnum.name())
                    .isEqualTo(apiEnum.getCode());
            assertThat(commonEnum.getMessage())
                    .as("shared enum %s should keep the same message", apiEnum.name())
                    .isEqualTo(apiEnum.getMessage());
        }
    }

    /**
     * Verifies that backend-common ResultEnum codes are unique before they are mapped from remote results.
     */
    @Test
    @DisplayName("should keep backend-common result enum codes unique")
    void shouldKeepBackendCommonResultEnumCodesUnique() {
        Map<Integer, Long> codeCounts = Arrays.stream(cn.flying.common.constant.ResultEnum.values())
                .collect(Collectors.groupingBy(cn.flying.common.constant.ResultEnum::getCode, Collectors.counting()));

        assertThat(codeCounts)
                .allSatisfy((code, count) -> assertThat(count)
                        .as("ResultEnum code %s should be unique", code)
                        .isEqualTo(1L));
    }
}
