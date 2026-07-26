package cn.flying.service.key;

/**
 * Provider 调用的显式成功或失败结果。
 */
public final class KeyWrappingResult<T> {

    private final T value;
    private final KeyWrappingFailure failure;

    private KeyWrappingResult(T value, KeyWrappingFailure failure) {
        this.value = value;
        this.failure = failure;
    }

    /**
     * 创建成功结果。
     */
    public static <T> KeyWrappingResult<T> success(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new KeyWrappingResult<>(value, null);
    }

    /**
     * 创建失败结果。
     */
    public static <T> KeyWrappingResult<T> failure(KeyWrappingFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new KeyWrappingResult<>(null, failure);
    }

    /**
     * 返回调用是否成功。
     */
    public boolean isSuccess() {
        return failure == null;
    }

    /**
     * 返回成功值，失败时抛出项目标准异常。
     */
    public T requireValue() {
        if (failure != null) {
            throw failure.toException();
        }
        return value;
    }

    /**
     * 返回成功值，仅供已检查状态的调用方使用。
     */
    public T value() {
        return value;
    }

    /**
     * 返回稳定失败信息。
     */
    public KeyWrappingFailure failure() {
        return failure;
    }

    @Override
    public String toString() {
        return isSuccess() ? "KeyWrappingResult[success]" : "KeyWrappingResult[" + failure + "]";
    }
}
