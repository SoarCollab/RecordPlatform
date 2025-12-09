package cn.flying.service.encryption;

import javax.crypto.SecretKey;

/**
 * 分片加密策略接口
 *
 * <p>定义文件分片加密/解密的通用契约，支持多种加密算法实现。</p>
 *
 * <h3>支持的实现：</h3>
 * <ul>
 *   <li>{@link AesGcmEncryptionStrategy} - AES-256-GCM (适合有 AES-NI 硬件加速的服务器)</li>
 *   <li>{@link ChaCha20EncryptionStrategy} - ChaCha20-Poly1305 (适合无硬件加速或混合环境)</li>
 * </ul>
 *
 * @author Claude Code
 * @since 2.0.0
 */
public interface ChunkEncryptionStrategy {

    /**
     * 获取算法名称（用于日志和调试）
     *
     * @return 算法标识符，如 "AES-256-GCM" 或 "ChaCha20-Poly1305"
     */
    String getAlgorithmName();

    /**
     * 获取 IV/Nonce 大小（字节）
     *
     * @return IV 大小
     */
    int getIvSize();

    /**
     * 获取认证标签大小（位）
     *
     * @return 标签位数，通常为 128
     */
    int getTagBitLength();

    /**
     * 生成新的加密密钥
     *
     * @return 256 位密钥
     */
    SecretKey generateKey();

    /**
     * 生成随机 IV/Nonce
     *
     * @return 随机 IV 字节数组
     */
    byte[] generateIv();

    /**
     * 加密数据
     *
     * @param plaintext 明文数据
     * @param key       加密密钥
     * @param iv        初始化向量
     * @return 密文（包含认证标签）
     * @throws EncryptionException 加密失败时抛出
     */
    byte[] encrypt(byte[] plaintext, SecretKey key, byte[] iv) throws EncryptionException;

    /**
     * 解密数据
     *
     * @param ciphertext 密文（包含认证标签）
     * @param key        解密密钥
     * @param iv         初始化向量
     * @return 明文数据
     * @throws EncryptionException 解密失败或认证失败时抛出
     */
    byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] iv) throws EncryptionException;

    /**
     * 流式加密 - 更新操作
     *
     * @param context 加密上下文
     * @param data    待加密数据块
     * @return 加密后的数据块（可能为空，取决于缓冲）
     * @throws EncryptionException 加密失败时抛出
     */
    byte[] encryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException;

    /**
     * 流式加密 - 完成操作
     *
     * @param context 加密上下文
     * @return 最终加密块（包含填充和认证标签）
     * @throws EncryptionException 加密失败时抛出
     */
    byte[] encryptFinal(EncryptionContext context) throws EncryptionException;

    /**
     * 创建加密上下文（用于流式加密）
     *
     * @param key 加密密钥
     * @param iv  初始化向量
     * @return 加密上下文
     * @throws EncryptionException 初始化失败时抛出
     */
    EncryptionContext createEncryptionContext(SecretKey key, byte[] iv) throws EncryptionException;

    /**
     * 流式解密 - 更新操作
     *
     * @param context 解密上下文
     * @param data    待解密数据块
     * @return 解密后的数据块
     * @throws EncryptionException 解密失败时抛出
     */
    byte[] decryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException;

    /**
     * 流式解密 - 完成操作
     *
     * @param context 解密上下文
     * @return 最终解密块
     * @throws EncryptionException 解密失败或认证失败时抛出
     */
    byte[] decryptFinal(EncryptionContext context) throws EncryptionException;

    /**
     * 创建解密上下文（用于流式解密）
     *
     * @param key 解密密钥
     * @param iv  初始化向量
     * @return 解密上下文
     * @throws EncryptionException 初始化失败时抛出
     */
    EncryptionContext createDecryptionContext(SecretKey key, byte[] iv) throws EncryptionException;
}
