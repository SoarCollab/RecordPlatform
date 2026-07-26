package cn.flying.service.key;

/**
 * Provider-neutral 的解封请求。
 */
public record KeyUnwrapRequest(PersistedWrappedDataKey source, WrappingContext context) {
}
