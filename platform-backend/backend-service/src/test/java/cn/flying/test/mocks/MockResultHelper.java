package cn.flying.test.mocks;

import cn.flying.platformapi.constant.Result;

/**
 * Helper class for creating mock Result objects in tests.
 * Simplifies the creation of success/failure responses from RPC calls.
 */
public class MockResultHelper {

    /**
     * Create a successful Result with data.
     */
    public static <T> Result<T> success(T data) {
        return Result.success(data);
    }

    /**
     * Create a successful Result without data.
     */
    public static <T> Result<T> success() {
        return Result.success(null);
    }

    /**
     * Create a failure Result with code and message.
     */
    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * Create a failure Result with default error code (500).
     */
    public static <T> Result<T> failure(String message) {
        return failure(500, message);
    }

    /**
     * Create a not found Result (404).
     */
    public static <T> Result<T> notFound(String message) {
        return failure(404, message);
    }

    /**
     * Create an unauthorized Result (401).
     */
    public static <T> Result<T> unauthorized(String message) {
        return failure(401, message);
    }

    /**
     * Create a service unavailable Result (503).
     */
    public static <T> Result<T> serviceUnavailable(String message) {
        return failure(503, message);
    }
}
