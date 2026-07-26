package cn.flying.service.key;

/**
 * Provider-neutral 的原生重包封请求。
 */
public record KeyRewrapRequest(
        PersistedWrappedDataKey source,
        WrappingContext sourceContext,
        WrappingKeyReference target,
        WrappingContext targetContext,
        Integer logicalKeyVersion
) {
}
