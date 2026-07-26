package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 负责 provider 注册、持久化路由和低基数观测的统一边界。
 */
@Service
public class KeyWrappingProviderRegistry {

    private static final String METRIC_NAME = "app.file.key_wrapping.duration";

    private final Map<ProviderKey, KeyWrappingProvider> providers;
    private final FileKeyEnvelopeProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * 构建 registry 并在启动时拒绝重复或未知 active provider。
     */
    public KeyWrappingProviderRegistry(List<KeyWrappingProvider> providerList,
                                       FileKeyEnvelopeProperties properties,
                                       MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Map<ProviderKey, KeyWrappingProvider> discovered = new LinkedHashMap<>();
        for (KeyWrappingProvider provider : providerList) {
            ProviderKey key = new ProviderKey(provider.providerId(), provider.contractVersion());
            if (!StringUtils.hasText(key.providerId()) || key.contractVersion() <= 0
                    || discovered.putIfAbsent(key, provider) != null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "密钥包封 provider 注册重复或无效");
            }
        }
        this.providers = Map.copyOf(discovered);
        requireActiveProvider();
    }

    /**
     * 返回当前 active provider 的新写入目标身份。
     */
    public KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion) {
        KeyWrappingProvider provider = requireActiveProvider();
        return observe(provider, "resolve", () -> provider.activeKeyReference(logicalKeyVersion));
    }

    /**
     * 路由 active provider 完成包封。
     */
    public KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request) {
        if (request == null || request.target() == null) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        KeyWrappingProvider provider = resolve(request.target().providerId(),
                request.target().providerContractVersion());
        if (provider == null) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        }
        return observe(provider, "wrap", () -> provider.wrap(request));
    }

    /**
     * 严格按持久化 provider id 与 contract version 路由解封。
     */
    public KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request) {
        if (request == null || request.source() == null || request.source().keyReference() == null) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        WrappingKeyReference source = request.source().keyReference();
        KeyWrappingProvider provider = resolve(source.providerId(), source.providerContractVersion());
        if (provider == null) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.CONFIGURATION, false));
        }
        return observe(provider, "unwrap", () -> provider.unwrap(request));
    }

    /**
     * 仅在同一 provider 实现声明能力时执行原生重包封。
     */
    public KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request) {
        if (request == null || request.source() == null || request.source().keyReference() == null
                || request.target() == null) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INVALID_REQUEST, false));
        }
        WrappingKeyReference source = request.source().keyReference();
        if (!source.providerId().equals(request.target().providerId())
                || source.providerContractVersion() != request.target().providerContractVersion()) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.UNSUPPORTED, false));
        }
        KeyWrappingProvider provider = resolve(source.providerId(), source.providerContractVersion());
        if (provider == null || !provider.capabilities().contains(KeyWrappingCapability.NATIVE_REWRAP_SAME_KEY)) {
            return KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.UNSUPPORTED, false));
        }
        return observe(provider, "rewrap", () -> provider.rewrap(request));
    }

    /**
     * 返回当前 active provider 的安全诊断摘要。
     */
    public KeyWrappingProviderDiagnostics activeDiagnostics() {
        return requireActiveProvider().diagnostics();
    }

    /**
     * 返回当前 active provider 的能力集合。
     */
    public boolean activeProviderSupports(KeyWrappingCapability capability) {
        return requireActiveProvider().capabilities().contains(capability);
    }

    /**
     * 按稳定复合身份解析 provider。
     */
    private KeyWrappingProvider resolve(String providerId, int contractVersion) {
        if (!StringUtils.hasText(providerId) || contractVersion <= 0) {
            return null;
        }
        return providers.get(new ProviderKey(providerId, contractVersion));
    }

    /**
     * 要求 active provider 显式存在且 id 唯一。
     */
    private KeyWrappingProvider requireActiveProvider() {
        String activeProvider = properties.getActiveProvider();
        Integer activeContractVersion = properties.getActiveProviderContractVersion();
        if (!StringUtils.hasText(activeProvider) || activeContractVersion == null || activeContractVersion <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "active key wrapping provider 不能为空");
        }
        KeyWrappingProvider provider = providers.get(new ProviderKey(activeProvider, activeContractVersion));
        if (provider == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "active key wrapping provider 未注册");
        }
        return provider;
    }

    /**
     * 捕获 provider 非预期异常并记录无高基数标签的耗时指标。
     */
    private <T> KeyWrappingResult<T> observe(KeyWrappingProvider provider,
                                             String operation,
                                             ProviderCall<T> call) {
        long started = System.nanoTime();
        KeyWrappingResult<T> result;
        try {
            result = call.invoke();
            if (result == null) {
                result = KeyWrappingResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.INTERNAL, false));
            }
        } catch (RuntimeException exception) {
            result = KeyWrappingResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INTERNAL, false));
        }
        String outcome = result.isSuccess() ? "success" : "failure";
        String failureCategory = result.isSuccess()
                ? KeyWrappingFailureCategory.NONE.name()
                : result.failure().category().name();
        Timer.builder(METRIC_NAME)
                .tag("provider", provider.providerId())
                .tag("operation", operation)
                .tag("outcome", outcome)
                .tag("failure_category", failureCategory)
                .register(meterRegistry)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        return result;
    }

    private record ProviderKey(String providerId, int contractVersion) {
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        KeyWrappingResult<T> invoke();
    }
}
