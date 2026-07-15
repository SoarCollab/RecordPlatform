package cn.flying.verifier.resolver;

/**
 * One bounded resolver result without throwing dependency details through the verifier.
 *
 * @param state resolver state
 * @param value resolved value when available
 * @param message safe diagnostic
 * @param <T> resolved value type
 */
public record Resolution<T>(
        ResolutionState state,
        T value,
        String message
) {

    /** Returns a successful resolution. */
    public static <T> Resolution<T> resolved(T value) {
        return new Resolution<>(ResolutionState.RESOLVED, value, null);
    }

    /** Returns a definitive missing record without inventing trust. */
    public static <T> Resolution<T> notFound(String message) {
        return new Resolution<>(ResolutionState.NOT_FOUND, null, message);
    }

    /** Returns a temporarily or intentionally unavailable dependency. */
    public static <T> Resolution<T> unavailable(String message) {
        return new Resolution<>(ResolutionState.UNAVAILABLE, null, message);
    }

    /** Returns a malformed or failed dependency response. */
    public static <T> Resolution<T> error(String message) {
        return new Resolution<>(ResolutionState.ERROR, null, message);
    }
}
