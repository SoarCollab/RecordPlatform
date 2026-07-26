package cn.flying.service.key;

/**
 * Provider-neutral 的包封请求。
 */
public record KeyWrapRequest(
        PlaintextDataKey plaintextDataKey,
        WrappingContext context,
        WrappingKeyReference target,
        Integer logicalKeyVersion
) {

    @Override
    public String toString() {
        return "KeyWrapRequest[plaintextDataKey=REDACTED, contextSchema="
                + (context == null ? "null" : context.schema()) + ", target=" + target
                + ", logicalKeyVersion=" + logicalKeyVersion + "]";
    }
}
