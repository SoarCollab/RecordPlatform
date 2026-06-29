package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Set;

/**
 * Validates configured crypto agility suite identifiers before metadata is persisted or exported.
 */
@Service
@RequiredArgsConstructor
public class CryptoSuitePolicyService {

    private final FileKeyEnvelopeProperties properties;

    /**
     * Returns validated current suite metadata for the supplied wrapping key version.
     */
    public CryptoSuiteMetadata currentMetadata(Integer keyVersion) {
        validateCurrentSuites();
        Integer resolvedKeyVersion = keyVersion != null ? keyVersion : properties.getKeyVersion();
        return new CryptoSuiteMetadata(
                properties.getAlgorithmSuite(),
                properties.getSignatureSuite(),
                properties.getKemSuite(),
                properties.getProofSuite(),
                resolvedKeyVersion,
                properties.getDeprecatedAfter()
        );
    }

    /**
     * Validates every configured active suite against supported and deprecated policy.
     */
    public void validateCurrentSuites() {
        validateSuite("algorithmSuite", properties.getAlgorithmSuite(), properties.getSupportedAlgorithmSuites());
        validateSuite("signatureSuite", properties.getSignatureSuite(), properties.getSupportedSignatureSuites());
        validateSuite("kemSuite", properties.getKemSuite(), properties.getSupportedKemSuites());
        validateSuite("proofSuite", properties.getProofSuite(), properties.getSupportedProofSuites());
        if (properties.getKeyVersion() == null || properties.getKeyVersion() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "keyVersion 必须为正整数");
        }
        if (properties.getDeprecatedAfter() != null && !properties.getDeprecatedAfter().isAfter(Instant.now())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "当前密码套件已废弃");
        }
    }

    /**
     * Resolves nullable persisted suite metadata to current defaults for old envelope rows.
     */
    public String defaultIfMissing(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    /**
     * Rejects blank, unsupported, or explicitly deprecated suite identifiers.
     */
    private void validateSuite(String field, String value, Set<String> supportedValues) {
        if (!StringUtils.hasText(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, field + " 不能为空");
        }
        if (supportedValues == null || !supportedValues.contains(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "不支持的密码套件: " + field + "=" + value);
        }
        if (properties.getDeprecatedSuites() != null && properties.getDeprecatedSuites().contains(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "已废弃的密码套件: " + field + "=" + value);
        }
    }
}
