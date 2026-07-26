package cn.flying.service.key;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Vault Transit HTTP 调用的可测试 transport seam。
 */
interface VaultTransitTransport {

    /**
     * 发送一次有界 POST 请求并返回安全分类结果。
     */
    VaultTransitHttpResult post(URI uri,
                                Map<String, String> headers,
                                byte[] body,
                                Duration requestTimeout,
                                int maxResponseBytes);
}
