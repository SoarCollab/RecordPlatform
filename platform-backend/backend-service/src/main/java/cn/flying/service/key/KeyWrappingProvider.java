package cn.flying.service.key;

import java.util.Set;

/**
 * 文件数据密钥包封 provider 的版本化 SPI。
 */
public interface KeyWrappingProvider {

    /**
     * 返回稳定 provider id。
     */
    String providerId();

    /**
     * 返回持久化路由使用的 contract version。
     */
    int contractVersion();

    /**
     * 返回 provider 能力声明。
     */
    Set<KeyWrappingCapability> capabilities();

    /**
     * 返回当前新写入的完整目标身份。
     */
    KeyWrappingResult<WrappingKeyReference> activeKeyReference(Integer logicalKeyVersion);

    /**
     * 包封明文数据密钥。
     */
    KeyWrappingResult<WrappedDataKey> wrap(KeyWrapRequest request);

    /**
     * 解封持久化数据密钥。
     */
    KeyWrappingResult<PlaintextDataKey> unwrap(KeyUnwrapRequest request);

    /**
     * 在 provider 支持时执行原生重包封。
     */
    KeyWrappingResult<WrappedDataKey> rewrap(KeyRewrapRequest request);

    /**
     * 返回不含 secret 的配置与可用性摘要。
     */
    KeyWrappingProviderDiagnostics diagnostics();
}
