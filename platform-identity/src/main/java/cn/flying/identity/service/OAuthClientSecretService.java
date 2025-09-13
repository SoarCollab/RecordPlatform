package cn.flying.identity.service;

/**
 * OAuth客户端密钥加密服务
 * 提供客户端密钥的加密存储和验证功能
 * 支持BCrypt加密和明文兼容模式
 *
 * @author flying
 * @date 2024
 */
public interface OAuthClientSecretService {

    /**
     * 加密客户端密钥
     * 根据配置决定是否使用BCrypt加密
     *
     * @param rawSecret 原始密钥
     * @return 加密后的密钥（如果启用加密）或原始密钥（如果未启用加密）
     */
    String encodeClientSecret(String rawSecret);

    /**
     * 验证客户端密钥
     * 支持BCrypt加密密钥和明文密钥的验证
     *
     * @param rawSecret     原始密钥
     * @param encodedSecret 存储的密钥（可能是加密的或明文的）
     * @return 是否匹配
     */
    boolean matches(String rawSecret, String encodedSecret);

    /**
     * 生成新的客户端密钥
     * 生成强度足够的随机密钥
     *
     * @return 新的客户端密钥（明文）
     */
    String generateClientSecret();

    /**
     * 检查密钥是否已加密
     * 根据密钥格式判断是否为BCrypt加密的密钥
     *
     * @param secret 密钥
     * @return 是否已加密
     */
    boolean isEncrypted(String secret);

    /**
     * 验证密钥强度
     * 确保客户端密钥满足安全要求
     *
     * @param secret 待验证的密钥
     * @return 是否满足强度要求
     */
    boolean validateSecretStrength(String secret);
}
