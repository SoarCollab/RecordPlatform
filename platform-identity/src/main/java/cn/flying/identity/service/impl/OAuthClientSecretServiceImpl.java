package cn.flying.identity.service.impl;

import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.service.OAuthClientSecretService;
import cn.flying.identity.service.PasswordService;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * OAuth客户端密钥加密服务实现类
 * 提供客户端密钥的BCrypt加密存储和验证功能
 * 支持向后兼容明文密钥的验证
 *
 * @author flying
 * @date 2024
 */
@Slf4j
@Service
public class OAuthClientSecretServiceImpl implements OAuthClientSecretService {

    /**
     * BCrypt密钥的正则表达式模式
     * BCrypt哈希通常以$2a$, $2b$, $2x$, $2y$开头
     */
    private static final Pattern BCRYPT_PATTERN = Pattern.compile("^\\$2[abxy]\\$\\d{2}\\$.{53}$");
    @Resource
    private OAuthConfig oauthConfig;
    @Resource
    private PasswordService passwordService;

    @Override
    public String encodeClientSecret(String rawSecret) {
        if (StrUtil.isBlank(rawSecret)) {
            throw new IllegalArgumentException("客户端密钥不能为空");
        }

        try {
            // 根据配置决定是否加密
            if (oauthConfig.isUseBcrypt()) {
                log.debug("使用BCrypt加密客户端密钥");
                return passwordService.encode(rawSecret);
            } else {
                log.debug("使用明文存储客户端密钥（仅开发环境）");
                return rawSecret;
            }
        } catch (Exception e) {
            log.error("客户端密钥加密失败: rawSecret length={}", rawSecret.length(), e);
            throw new RuntimeException("客户端密钥加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean matches(String rawSecret, String encodedSecret) {
        if (StrUtil.isBlank(rawSecret) || StrUtil.isBlank(encodedSecret)) {
            log.warn("客户端密钥验证参数为空: rawSecret={}, encodedSecret={}",
                    StrUtil.isBlank(rawSecret) ? "blank" : "present",
                    StrUtil.isBlank(encodedSecret) ? "blank" : "present");
            return false;
        }

        try {
            // 检查存储的密钥是否已加密
            if (isEncrypted(encodedSecret)) {
                // 使用BCrypt验证加密的密钥
                log.debug("验证BCrypt加密的客户端密钥");
                return passwordService.matches(rawSecret, encodedSecret);
            } else {
                // 向后兼容：直接比较明文密钥
                log.debug("验证明文客户端密钥（向后兼容模式）");
                return rawSecret.equals(encodedSecret);
            }
        } catch (Exception e) {
            log.error("客户端密钥验证失败", e);
            return false;
        }
    }

    @Override
    public String generateClientSecret() {
        try {
            // 生成32位随机字符串作为客户端密钥
            // 包含大小写字母和数字，确保足够的强度
            String secret = RandomUtil.randomString(
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                    32
            );

            log.debug("生成新的客户端密钥，长度: {}", secret.length());
            return secret;
        } catch (Exception e) {
            log.error("生成客户端密钥失败", e);
            // 备用方案：使用IdUtil生成UUID
            return IdUtil.fastSimpleUUID().substring(0, 32);
        }
    }

    @Override
    public boolean isEncrypted(String secret) {
        if (StrUtil.isBlank(secret)) {
            return false;
        }

        // 检查是否符合BCrypt哈希格式
        boolean isBcrypt = BCRYPT_PATTERN.matcher(secret).matches();

        log.debug("检查密钥是否已加密: length={}, isBcrypt={}", secret.length(), isBcrypt);
        return isBcrypt;
    }

    /**
     * 验证密钥强度
     * 确保客户端密钥满足安全要求
     *
     * @param secret 待验证的密钥
     * @return 是否满足强度要求
     */
    public boolean validateSecretStrength(String secret) {
        if (StrUtil.isBlank(secret)) {
            return false;
        }

        // 最小长度要求
        if (secret.length() < 16) {
            log.warn("客户端密钥长度不足: {}, 最小要求16位", secret.length());
            return false;
        }

        // 检查字符复杂度（至少包含字母和数字）
        boolean hasLetter = secret.matches(".*[a-zA-Z].*");
        boolean hasDigit = secret.matches(".*\\d.*");

        if (!hasLetter || !hasDigit) {
            log.warn("客户端密钥复杂度不足: hasLetter={}, hasDigit={}", hasLetter, hasDigit);
            return false;
        }

        return true;
    }

}
