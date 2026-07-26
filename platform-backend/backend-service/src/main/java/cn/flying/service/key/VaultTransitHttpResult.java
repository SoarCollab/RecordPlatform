package cn.flying.service.key;

/**
 * JDK HTTP transport 的有界 Vault 响应结果。
 */
record VaultTransitHttpResult(
        Integer statusCode,
        byte[] body,
        KeyWrappingFailure transportFailure
) {

    /**
     * 创建成功到达 Vault 的 HTTP 响应。
     */
    static VaultTransitHttpResult response(int statusCode, byte[] body) {
        return new VaultTransitHttpResult(statusCode, body, null);
    }

    /**
     * 创建未到达稳定 HTTP 响应的 transport 失败。
     */
    static VaultTransitHttpResult failure(KeyWrappingFailure failure) {
        return new VaultTransitHttpResult(null, null, failure);
    }

    /**
     * 返回 transport 是否得到 HTTP 状态。
     */
    boolean hasResponse() {
        return transportFailure == null && statusCode != null;
    }

    @Override
    public String toString() {
        return "VaultTransitHttpResult[statusCode=" + statusCode
                + ", body=REDACTED, transportFailure=" + transportFailure + "]";
    }
}
