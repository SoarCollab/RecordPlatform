package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.FileKeyEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Local AES-GCM wrapper for serialized file data-key tokens.
 */
@Service
@RequiredArgsConstructor
public class LocalKeyWrappingService {

    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String AES_ALGORITHM = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final FileKeyEnvelopeProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Encrypts a serialized file data-key token and binds it to the supplied AAD.
     */
    public WrappedDataKey wrap(String plaintextKey, byte[] aad) {
        if (!StringUtils.hasText(plaintextKey)) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥不能为空");
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, resolveMasterKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            byte[] encrypted = cipher.doFinal(plaintextKey.getBytes(StandardCharsets.UTF_8));
            return new WrappedDataKey(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv),
                    properties.getProvider(),
                    properties.getKmsKeyId(),
                    properties.getKeyVersion(),
                    properties.getWrappingAlgorithm()
            );
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥封装失败");
        }
    }

    /**
     * Decrypts a serialized file data-key token from a persisted envelope.
     */
    public String unwrap(FileKeyEnvelope envelope, byte[] aad) {
        if (envelope == null || !StringUtils.hasText(envelope.getEncryptedDataKey())
                || !StringUtils.hasText(envelope.getWrappingIv())) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥信封不完整");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(envelope.getWrappingIv());
            byte[] encrypted = Base64.getDecoder().decode(envelope.getEncryptedDataKey());

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveMasterKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件数据密钥解封失败");
        }
    }

    /**
     * Derives a stable AES-256 key from the deployment local master key.
     */
    private SecretKeySpec resolveMasterKey() {
        String masterKey = properties.getLocalMasterKey();
        if (!StringUtils.hasText(masterKey)) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封主密钥未配置");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest(masterKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, AES_ALGORITHM);
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封主密钥派生失败");
        }
    }
}
