package cn.flying.service.key;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于 Java 21 HttpClient 的 Vault Transit transport。
 */
@Component
class JdkVaultTransitTransport implements VaultTransitTransport {

    private final HttpClient httpClient;

    /**
     * 使用配置的 connect timeout 构建共享 JDK HTTP client。
     */
    JdkVaultTransitTransport(FileKeyEnvelopeProperties properties) {
        Duration connectTimeout = properties.getProviders().getVaultTransit().getConnectTimeout();
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = Duration.ofSeconds(2);
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    /**
     * 发送一次 POST，并在读取时强制响应体大小上限。
     */
    @Override
    public VaultTransitHttpResult post(URI uri,
                                       Map<String, String> headers,
                                       byte[] body,
                                       Duration requestTimeout,
                                       int maxResponseBytes) {
        long started = System.nanoTime();
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(requestBuilder::header);
            HttpResponse<InputStream> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream responseBody = response.body()) {
                return readBoundedResponseBody(
                        response.statusCode(), responseBody, requestTimeout, started, maxResponseBytes);
            }
        } catch (HttpTimeoutException exception) {
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.TIMEOUT, true));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.UNAVAILABLE, true));
        } catch (IOException exception) {
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.UNAVAILABLE, true));
        } catch (RuntimeException exception) {
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.INTERNAL, false));
        }
    }

    /**
     * 在请求剩余 deadline 内读取有界响应体，避免流式 body 绕过 HttpRequest timeout。
     */
    private VaultTransitHttpResult readBoundedResponseBody(int statusCode,
                                                           InputStream responseBody,
                                                           Duration requestTimeout,
                                                           long started,
                                                           int maxResponseBytes) {
        long remainingNanos = requestTimeout.toNanos() - (System.nanoTime() - started);
        if (remainingNanos <= 0) {
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.TIMEOUT, true));
        }
        CompletableFuture<byte[]> bodyFuture = new CompletableFuture<>();
        Thread reader = Thread.ofVirtual().name("vault-transit-response-reader").start(() -> {
            try {
                bodyFuture.complete(responseBody.readNBytes(maxResponseBytes + 1));
            } catch (IOException | RuntimeException exception) {
                bodyFuture.completeExceptionally(exception);
            }
        });
        try {
            byte[] boundedBody = bodyFuture.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (boundedBody.length > maxResponseBytes) {
                return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                        KeyWrappingFailureCategory.INVALID_RESPONSE, false));
            }
            return VaultTransitHttpResult.response(statusCode, boundedBody);
        } catch (TimeoutException exception) {
            reader.interrupt();
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.TIMEOUT, true));
        } catch (InterruptedException exception) {
            reader.interrupt();
            Thread.currentThread().interrupt();
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(
                    KeyWrappingFailureCategory.UNAVAILABLE, true));
        } catch (ExecutionException exception) {
            KeyWrappingFailureCategory category = exception.getCause() instanceof IOException
                    ? KeyWrappingFailureCategory.UNAVAILABLE
                    : KeyWrappingFailureCategory.INTERNAL;
            return VaultTransitHttpResult.failure(KeyWrappingFailure.of(category,
                    category == KeyWrappingFailureCategory.UNAVAILABLE));
        }
    }
}
