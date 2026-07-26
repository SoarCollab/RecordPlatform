package cn.flying.service.key;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdkVaultTransitTransportTest {

    /**
     * 验证响应头到达后停滞的流式 body 仍受完整请求 deadline 约束。
     */
    @Test
    void shouldTimeoutWhileReadingStreamingResponseBody() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        try (var serverExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverExecutor);
            server.createContext("/v1/transit/decrypt/file-key", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write('{');
                    responseBody.flush();
                    releaseBody.await(5, TimeUnit.SECONDS);
                    responseBody.write("\"data\":{}}".getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            try {
                FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
                properties.getProviders().getVaultTransit().setConnectTimeout(Duration.ofSeconds(1));
                JdkVaultTransitTransport transport = new JdkVaultTransitTransport(properties);
                URI endpoint = URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/v1/transit/decrypt/file-key");

                long started = System.nanoTime();
                VaultTransitHttpResult result = transport.post(
                        endpoint,
                        Map.of("X-Vault-Token", "test-token"),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        Duration.ofMillis(150),
                        1_024);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(result.hasResponse()).isFalse();
                assertThat(result.transportFailure().category())
                        .isEqualTo(KeyWrappingFailureCategory.TIMEOUT);
                assertThat(result.transportFailure().retryable()).isTrue();
                assertThat(elapsedMillis).isLessThan(2_000);
            } finally {
                releaseBody.countDown();
                server.stop(0);
            }
        }
    }
}
