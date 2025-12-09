package cn.flying.service.encryption;

import javax.crypto.Cipher;

/**
 * 加密/解密上下文
 *
 * <p>封装流式加密操作所需的状态，支持分块处理大文件。</p>
 */
public class EncryptionContext {

    private final Cipher cipher;
    private final String algorithm;

    public EncryptionContext(Cipher cipher, String algorithm) {
        this.cipher = cipher;
        this.algorithm = algorithm;
    }

    public Cipher getCipher() {
        return cipher;
    }

    public String getAlgorithm() {
        return algorithm;
    }
}
