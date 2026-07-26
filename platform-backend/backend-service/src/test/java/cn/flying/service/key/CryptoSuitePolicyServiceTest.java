package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证文件元数据显式算法套件的 allowlist 与废弃策略。
 */
@DisplayName("CryptoSuitePolicyService")
class CryptoSuitePolicyServiceTest {

    private static final String LEGACY_SUITE = "RP-AES256-GCM-CHUNK-CHAIN-V1";
    private static final String FRAMED_SUITE = "RP-AES256-GCM-FRAMED-V2";

    private FileKeyEnvelopeProperties properties;
    private CryptoSuitePolicyService policyService;

    /**
     * 为每个用例创建独立的密码套件配置，避免废弃状态相互污染。
     */
    @BeforeEach
    void setUp() {
        properties = new FileKeyEnvelopeProperties();
        properties.setSupportedAlgorithmSuites(
                new LinkedHashSet<>(Set.of(LEGACY_SUITE, FRAMED_SUITE)));
        policyService = new CryptoSuitePolicyService(properties);
    }

    /**
     * 验证 allowlist 内的 legacy 和 framed 套件均可用于受控 writer 回滚与升级。
     */
    @Test
    void validateAlgorithmSuite_shouldAcceptSupportedSuites() {
        assertDoesNotThrow(() -> policyService.validateAlgorithmSuite(LEGACY_SUITE));
        assertDoesNotThrow(() -> policyService.validateAlgorithmSuite(FRAMED_SUITE));
    }

    /**
     * 验证空值和 allowlist 外的套件返回参数错误。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectBlankAndUnsupportedSuites() {
        GeneralException nullSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(null));
        GeneralException blankSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite("  "));
        GeneralException unsupportedSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite("UNKNOWN-SUITE"));

        assertThat(nullSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(blankSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(unsupportedSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
    }

    /**
     * 验证显式废弃列表中的套件即使仍在 allowlist 中也必须被拒绝。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectExplicitlyDeprecatedSuite() {
        properties.setDeprecatedSuites(new LinkedHashSet<>(Set.of(FRAMED_SUITE)));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(FRAMED_SUITE));

        assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(error.getData()).asString().contains("已废弃的密码套件");
    }

    /**
     * 验证全局废弃时间到期后，当前显式算法套件也停止接受。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectSuiteAfterGlobalDeprecation() {
        properties.setDeprecatedAfter(Instant.now().minusSeconds(1));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(FRAMED_SUITE));

        assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(error.getData()).asString().contains("当前密码套件已废弃");
    }
}
