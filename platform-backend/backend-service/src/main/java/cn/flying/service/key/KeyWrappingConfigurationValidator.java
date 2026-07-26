package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 在应用启动阶段校验 active key wrapping provider 的安全配置。
 */
@Component
public class KeyWrappingConfigurationValidator implements InitializingBean {

    private static final int MIN_PRODUCTION_LOCAL_KEY_LENGTH = 32;

    private final FileKeyEnvelopeProperties properties;
    private final KeyWrappingProviderRegistry registry;
    private final Environment environment;

    /**
     * 创建 profile-aware 的 provider 配置校验器。
     */
    public KeyWrappingConfigurationValidator(FileKeyEnvelopeProperties properties,
                                             KeyWrappingProviderRegistry registry,
                                             Environment environment) {
        this.properties = properties;
        this.registry = registry;
        this.environment = environment;
    }

    /**
     * 在属性绑定完成后执行 fail-fast 校验。
     */
    @Override
    public void afterPropertiesSet() {
        KeyWrappingProviderDiagnostics diagnostics = registry.activeDiagnostics();
        if (!diagnostics.available()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "active key wrapping provider 配置不完整");
        }
        if (LocalKeyWrappingService.PROVIDER_ID.equals(diagnostics.providerId())) {
            validateLocalConfiguration();
        } else if (VaultTransitKeyWrappingProvider.PROVIDER_ID.equals(diagnostics.providerId())) {
            validateVaultConfiguration();
        }
    }

    /**
     * 校验 local provider 当前版本拥有可用 master key，生产还必须与 JWT key 分离。
     */
    private void validateLocalConfiguration() {
        Integer keyVersion = properties.getKeyVersion();
        String masterKey = properties.getLocalMasterKeys().get(keyVersion);
        if (!StringUtils.hasText(masterKey)) {
            masterKey = properties.getLocalMasterKey();
        }
        if (!StringUtils.hasText(properties.getKmsKeyId()) || !StringUtils.hasText(masterKey)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "local key wrapping provider 配置不完整");
        }
        if (isProduction()) {
            if (masterKey.length() < MIN_PRODUCTION_LOCAL_KEY_LENGTH) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "生产 local master key 长度不足");
            }
            String jwtKey = environment.getProperty("spring.security.jwt.key");
            if (StringUtils.hasText(jwtKey) && constantTimeEquals(masterKey, jwtKey)) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "生产 local master key 禁止复用 JWT key");
            }
        }
    }

    /**
     * 校验 Vault provider 配置，生产只允许 HTTPS。
     */
    private void validateVaultConfiguration() {
        FileKeyEnvelopeProperties.VaultTransit vault = properties.getProviders().getVaultTransit();
        if (isProduction() && (vault.isAllowHttp()
                || !StringUtils.hasText(vault.getAddress())
                || !vault.getAddress().toLowerCase(java.util.Locale.ROOT).startsWith("https://"))) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "生产 Vault Transit 必须使用 HTTPS");
        }
    }

    /**
     * 判断当前应用是否启用 prod profile。
     */
    private boolean isProduction() {
        return Arrays.asList(environment.getActiveProfiles()).contains("prod");
    }

    /**
     * 常量时间比较两个敏感配置值。
     */
    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
