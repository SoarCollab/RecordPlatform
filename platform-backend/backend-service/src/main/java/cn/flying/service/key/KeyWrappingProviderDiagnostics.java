package cn.flying.service.key;

import java.util.Set;

/**
 * 可安全暴露给健康检查的 provider 诊断信息。
 */
public record KeyWrappingProviderDiagnostics(
        String providerId,
        int contractVersion,
        Set<KeyWrappingCapability> capabilities,
        boolean available,
        String configurationState
) {
}
