package cn.flying.service.encryption;

/**
 * 加密/解密异常
 *
 * <p>包装底层加密操作中可能出现的各种异常。</p>
 */
public class EncryptionException extends Exception {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
