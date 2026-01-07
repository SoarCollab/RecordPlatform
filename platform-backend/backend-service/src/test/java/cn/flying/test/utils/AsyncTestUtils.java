package cn.flying.test.utils;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public class AsyncTestUtils {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(100);

    public static <T> T awaitFuture(CompletableFuture<T> future) {
        return awaitFuture(future, DEFAULT_TIMEOUT);
    }

    public static <T> T awaitFuture(CompletableFuture<T> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Future was interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Future execution failed", e.getCause());
        } catch (TimeoutException e) {
            throw new RuntimeException("Future timed out after " + timeout.toMillis() + "ms", e);
        }
    }

    public static void awaitCondition(BooleanSupplier condition) {
        awaitCondition(condition, DEFAULT_TIMEOUT);
    }

    public static void awaitCondition(BooleanSupplier condition, Duration timeout) {
        awaitCondition(condition, timeout, DEFAULT_POLL_INTERVAL, "Condition not met");
    }

    public static void awaitCondition(BooleanSupplier condition, Duration timeout, Duration pollInterval, String failureMessage) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeout.toMillis();
        long pollMillis = pollInterval.toMillis();

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                throw new RuntimeException(failureMessage + " (timed out after " + timeoutMillis + "ms)");
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Condition check was interrupted", e);
            }
        }
    }

    public static <T> void awaitAllFutures(List<CompletableFuture<T>> futures) {
        awaitAllFutures(futures, DEFAULT_TIMEOUT);
    }

    public static <T> void awaitAllFutures(List<CompletableFuture<T>> futures, Duration timeout) {
        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        awaitFuture(allOf.thenApply(v -> null), timeout);
    }

    public static <T> T awaitAnyFuture(List<CompletableFuture<T>> futures) {
        return awaitAnyFuture(futures, DEFAULT_TIMEOUT);
    }

    @SuppressWarnings("unchecked")
    public static <T> T awaitAnyFuture(List<CompletableFuture<T>> futures, Duration timeout) {
        CompletableFuture<Object> anyOf = CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]));
        return (T) awaitFuture(anyOf, timeout);
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep was interrupted", e);
        }
    }

    public static void sleepMillis(long millis) {
        sleep(Duration.ofMillis(millis));
    }

    public static void sleepSeconds(int seconds) {
        sleep(Duration.ofSeconds(seconds));
    }

    /**
     * Creates a test SseEmitter that captures sent events.
     * Use this instead of trying to capture events from an existing emitter.
     *
     * @return a TestSseEmitter that records all sent events
     */
    public static TestSseEmitter createCapturingSseEmitter() {
        return new TestSseEmitter();
    }

    /**
     * Creates a test SseEmitter with custom timeout.
     *
     * @param timeout emitter timeout in milliseconds
     * @return a TestSseEmitter that records all sent events
     */
    public static TestSseEmitter createCapturingSseEmitter(long timeout) {
        return new TestSseEmitter(timeout);
    }

    /**
     * Test SseEmitter that captures all sent events for verification.
     * Thread-safe implementation using CopyOnWriteArrayList.
     */
    public static class TestSseEmitter extends SseEmitter {
        private final List<Object> capturedEvents = new CopyOnWriteArrayList<>();
        private final List<SseEventBuilder> capturedEventBuilders = new CopyOnWriteArrayList<>();

        public TestSseEmitter() {
            super();
        }

        public TestSseEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(Object object) throws java.io.IOException {
            capturedEvents.add(object);
            super.send(object);
        }

        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            capturedEventBuilders.add(builder);
            super.send(builder);
        }

        public List<Object> getCapturedEvents() {
            return new ArrayList<>(capturedEvents);
        }

        public List<SseEventBuilder> getCapturedEventBuilders() {
            return new ArrayList<>(capturedEventBuilders);
        }

        public int getCapturedEventCount() {
            return capturedEvents.size() + capturedEventBuilders.size();
        }

        public void clearCapturedEvents() {
            capturedEvents.clear();
            capturedEventBuilders.clear();
        }
    }

    public static <T> T retryUntilSuccess(java.util.concurrent.Callable<T> action, int maxRetries, Duration delayBetweenRetries) {
        Exception lastException = null;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return action.call();
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries - 1) {
                    sleep(delayBetweenRetries);
                }
            }
        }
        throw new RuntimeException("Action failed after " + maxRetries + " retries", lastException);
    }

    public static void assertEventually(Runnable assertion, Duration timeout) {
        awaitCondition(() -> {
            try {
                assertion.run();
                return true;
            } catch (AssertionError e) {
                return false;
            }
        }, timeout, DEFAULT_POLL_INTERVAL, "Assertion did not pass within timeout");
    }
}
