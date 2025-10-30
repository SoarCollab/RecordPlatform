package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiKeyService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * API密钥管理服务实现类，採用 BusinessException + RestResponse 模式
 */
@Slf4j
@Service
public class ApiKeyServiceImpl extends BaseService implements ApiKeyService {

    private static final String REDIS_KEY_PREFIX = "api:key:";
    private static final String NONCE_PREFIX = "api:nonce:";

    @Resource
    private ApiKeyMapper apiKeyMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * AES加密密钥配置
     */
    @Value("${api.key.aes-secret:record-platform-api-key-secret-2025}")
    private String aesSecretKey;

    /**
     * 防重放窗口（秒）
     */
    @Value("${api.key.replay-window:300}")
    private long replayWindow;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateApiKey(Long appId, String keyName, Integer keyType, Integer expireDays) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(keyType, "密钥类型不能为空");
        requireCondition(keyType, type -> type == 1 || type == 2, "密钥类型必须是1(正式)或2(测试)");

        String apiKey = "ak_" + RandomUtil.randomString(32);
        String apiSecret = "sk_" + RandomUtil.randomString(48);
        String encryptedSecret = encryptSecret(apiSecret);

        ApiKey key = new ApiKey();
        key.setId(IdUtils.nextEntityId());
        key.setAppId(appId);
        key.setApiKey(apiKey);
        key.setApiSecret(encryptedSecret);
        key.setKeyName(getOrElse(keyName, "默认密钥"));
        key.setKeyStatus(1);
        key.setKeyType(keyType);
        key.setUsedCount(0L);

        LocalDateTime now = LocalDateTime.now();
        key.setCreateTime(now);
        key.setUpdateTime(now);

        if (expireDays != null && expireDays > 0) {
            key.setExpireTime(now.plusDays(expireDays));
        }

        int inserted = apiKeyMapper.insert(key);
        requireCondition(inserted, count -> count > 0, ResultEnum.SYSTEM_ERROR, "生成密钥失败");

        cacheApiKey(key);

        Map<String, Object> result = new HashMap<>();
        result.put("key_id", key.getId());
        result.put("app_id", appId);
        result.put("api_key", apiKey);
        result.put("api_secret", apiSecret);
        result.put("key_name", key.getKeyName());
        result.put("key_type", keyType);
        result.put("expire_time", key.getExpireTime());
        result.put("create_time", key.getCreateTime());

        logInfo("生成API密钥成功: appId={}, keyId={}, keyType={}", appId, key.getId(), keyType);
        return result;
    }

    @Override
    public Map<String, Object> validateApiKey(String apiKey, Long timestamp, String nonce,
                                              String signature, String requestData) {
        requireNonBlank(apiKey, "API密钥不能为空");
        requireNonNull(timestamp, "时间戳不能为空");
        requireNonBlank(nonce, "随机字符串不能为空");
        requireNonBlank(signature, "签名不能为空");

        try {
            long currentTime = System.currentTimeMillis() / 1000;
            if (Math.abs(currentTime - timestamp) > replayWindow) {
                throw businessException(ResultEnum.PERMISSION_TOKEN_EXPIRED, "请求已过期");
            }

            String nonceKey = NONCE_PREFIX + nonce;
            if (Boolean.TRUE.equals(redisTemplate.hasKey(nonceKey))) {
                throw businessException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "Nonce已被使用");
            }

            ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
            if (keyInfo == null) {
                throw businessException(ResultEnum.PERMISSION_TOKEN_INVALID, "API密钥无效");
            }

            ensureKeyUsable(keyInfo);

            String decryptedSecret = decryptSecret(keyInfo.getApiSecret());
            String expectedSignature = generateSignature(apiKey, decryptedSecret, timestamp, nonce, requestData);
            if (!expectedSignature.equals(signature)) {
                throw businessException(ResultEnum.PERMISSION_SIGNATURE_ERROR, "签名无效");
            }

            redisTemplate.opsForValue().set(nonceKey, "1", replayWindow * 2, TimeUnit.SECONDS);
            updateKeyUsageAsync(keyInfo.getId());

            Map<String, Object> result = new HashMap<>();
            result.put("app_id", keyInfo.getAppId());
            result.put("key_id", keyInfo.getId());
            result.put("key_name", keyInfo.getKeyName());
            result.put("key_type", keyInfo.getKeyType());

            logDebug("API密钥验证成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            logError("API密钥验证异常", ex);
            throw businessException(ResultEnum.SYSTEM_ERROR, "API密钥验证失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableKey(Long keyId) {
        requireNonNull(keyId, "密钥ID不能为空");

        ApiKey key = apiKeyMapper.selectById(keyId);
        requireNonNull(key, ResultEnum.RESULT_DATA_NONE, "密钥不存在");

        key.setKeyStatus(1);
        key.setUpdateTime(LocalDateTime.now());
        int updated = apiKeyMapper.updateById(key);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "启用密钥失败");

        cacheApiKey(key);
        logInfo("启用API密钥成功: keyId={}", keyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableKey(Long keyId) {
        requireNonNull(keyId, "密钥ID不能为空");

        ApiKey key = apiKeyMapper.selectById(keyId);
        requireNonNull(key, ResultEnum.RESULT_DATA_NONE, "密钥不存在");

        key.setKeyStatus(0);
        key.setUpdateTime(LocalDateTime.now());
        int updated = apiKeyMapper.updateById(key);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "禁用密钥失败");

        clearKeyCache(key.getApiKey());
        logInfo("禁用API密钥成功: keyId={}", keyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKey(Long keyId) {
        requireNonNull(keyId, "密钥ID不能为空");

        ApiKey key = apiKeyMapper.selectById(keyId);
        requireNonNull(key, ResultEnum.RESULT_DATA_NONE, "密钥不存在");

        int deleted = apiKeyMapper.deleteById(keyId);
        requireCondition(deleted, count -> count > 0, ResultEnum.SYSTEM_ERROR, "删除密钥失败");

        clearKeyCache(key.getApiKey());
        logInfo("删除API密钥成功: keyId={}", keyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rotateKey(Long oldKeyId) {
        requireNonNull(oldKeyId, "旧密钥ID不能为空");

        ApiKey oldKey = apiKeyMapper.selectById(oldKeyId);
        requireNonNull(oldKey, ResultEnum.RESULT_DATA_NONE, "旧密钥不存在");

        Map<String, Object> newKeyInfo = generateApiKey(
                oldKey.getAppId(),
                oldKey.getKeyName() + "_rotated",
                oldKey.getKeyType(),
                null
        );

        oldKey.setKeyStatus(0);
        oldKey.setUpdateTime(LocalDateTime.now());
        apiKeyMapper.updateById(oldKey);
        clearKeyCache(oldKey.getApiKey());

        newKeyInfo.put("old_key_id", oldKeyId);
        newKeyInfo.put("rotated_at", LocalDateTime.now());

        logInfo("轮换API密钥成功: oldKeyId={}, newKeyId={}", oldKeyId, newKeyInfo.get("key_id"));
        return newKeyInfo;
    }

    @Override
    public List<ApiKey> getKeysByAppId(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiKey::getAppId, appId)
                .orderByDesc(ApiKey::getCreateTime);

        List<ApiKey> keys = apiKeyMapper.selectList(wrapper);
        return keys != null ? keys : new ArrayList<>();
    }

    @Override
    public ApiKey getKeyById(Long keyId) {
        requireNonNull(keyId, "密钥ID不能为空");
        ApiKey key = apiKeyMapper.selectById(keyId);
        requireNonNull(key, ResultEnum.RESULT_DATA_NONE, "密钥不存在");
        return key;
    }

    @Override
    public void updateLastUsedTime(Long keyId) {
        requireNonNull(keyId, "密钥ID不能为空");

        ApiKey key = new ApiKey();
        key.setId(keyId);
        key.setLastUsedTime(LocalDateTime.now());
        key.setUpdateTime(LocalDateTime.now());

        int updated = apiKeyMapper.updateById(key);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "更新最后使用时间失败");
    }

    @Override
    public List<ApiKey> getExpiringKeys(int days) {
        requireCondition(days, d -> d > 0, "提前天数必须大于0");

        LocalDateTime threshold = LocalDateTime.now().plusDays(days);

        LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiKey::getKeyStatus, 1)
                .isNotNull(ApiKey::getExpireTime)
                .le(ApiKey::getExpireTime, threshold);

        List<ApiKey> keys = apiKeyMapper.selectList(wrapper);
        return keys != null ? keys : new ArrayList<>();
    }

    @Override
    public void validateApiKey(String apiKey) {
        requireNonBlank(apiKey, "API密钥不能为空");
        try {
            ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
            if (keyInfo == null) {
                throw businessException(ResultEnum.PERMISSION_TOKEN_INVALID, "API密钥无效");
            }
            ensureKeyUsable(keyInfo);
            updateKeyUsageAsync(keyInfo.getId());
            logDebug("API密钥简化验证成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            logError("API密钥简化验证异常", ex);
            throw businessException(ResultEnum.SYSTEM_ERROR, "API密钥验证失败");
        }
    }

    @Override
    public ApiKey getKeyInfoByApiKey(String apiKey) {
        requireNonBlank(apiKey, "API密钥不能为空");

        ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
        if (keyInfo == null) {
            throw businessException(ResultEnum.PERMISSION_TOKEN_INVALID, "API密钥不存在");
        }

        ensureKeyUsable(keyInfo);
        updateKeyUsageAsync(keyInfo.getId());

        logDebug("获取API密钥信息成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
        return keyInfo;
    }

    private void ensureKeyUsable(ApiKey keyInfo) {
        if (keyInfo.getKeyStatus() == null || keyInfo.getKeyStatus() != 1) {
            throw businessException(ResultEnum.PERMISSION_TOKEN_INVALID, "API密钥已禁用");
        }
        if (keyInfo.getExpireTime() != null && LocalDateTime.now().isAfter(keyInfo.getExpireTime())) {
            throw businessException(ResultEnum.PERMISSION_TOKEN_EXPIRED, "API密钥已过期");
        }
    }

    private String encryptSecret(String secret) {
        try {
            byte[] keyBytes = aesSecretKey.getBytes(StandardCharsets.UTF_8);
            keyBytes = Arrays.copyOf(keyBytes, 32);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "加密密钥失败");
        }
    }

    private String decryptSecret(String encryptedSecret) {
        try {
            byte[] keyBytes = aesSecretKey.getBytes(StandardCharsets.UTF_8);
            keyBytes = Arrays.copyOf(keyBytes, 32);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            byte[] combined = Base64.getDecoder().decode(encryptedSecret);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);

            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "解密密钥失败");
        }
    }

    private String generateSignature(String apiKey, String apiSecret, Long timestamp,
                                     String nonce, String requestData) {
        StringBuilder signBuilder = new StringBuilder();
        signBuilder.append(apiKey).append("\n");
        signBuilder.append(timestamp).append("\n");
        signBuilder.append(nonce).append("\n");
        if (requestData != null && !requestData.isEmpty()) {
            String dataHash = SecureUtil.sha256(requestData);
            signBuilder.append(dataHash);
        } else {
            signBuilder.append("");
        }
        String signData = signBuilder.toString();
        return SecureUtil.hmacSha256(apiSecret).digestHex(signData);
    }

    private void cacheApiKey(ApiKey key) {
        try {
            String cacheKey = REDIS_KEY_PREFIX + key.getApiKey();
            Map<String, String> keyInfo = new HashMap<>();
            keyInfo.put("id", String.valueOf(key.getId()));
            keyInfo.put("app_id", String.valueOf(key.getAppId()));
            keyInfo.put("api_secret", key.getApiSecret());
            keyInfo.put("key_status", String.valueOf(key.getKeyStatus()));
            keyInfo.put("key_name", key.getKeyName());
            keyInfo.put("key_type", String.valueOf(key.getKeyType()));
            if (key.getExpireTime() != null) {
                keyInfo.put("expire_time", key.getExpireTime().toString());
            }

            redisTemplate.opsForHash().putAll(cacheKey, keyInfo);
            redisTemplate.expire(cacheKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("缓存密钥信息失败", e);
        }
    }

    private ApiKey getKeyFromCacheOrDb(String apiKey) {
        try {
            String cacheKey = REDIS_KEY_PREFIX + apiKey;
            Map<Object, Object> cachedData = redisTemplate.opsForHash().entries(cacheKey);

            if (cachedData != null && !cachedData.isEmpty()) {
                ApiKey key = new ApiKey();
                key.setId(Long.parseLong((String) cachedData.get("id")));
                key.setAppId(Long.parseLong((String) cachedData.get("app_id")));
                key.setApiKey(apiKey);
                key.setApiSecret((String) cachedData.get("api_secret"));
                key.setKeyStatus(Integer.parseInt((String) cachedData.get("key_status")));
                key.setKeyName((String) cachedData.get("key_name"));
                key.setKeyType(Integer.parseInt((String) cachedData.get("key_type")));
                if (cachedData.containsKey("expire_time")) {
                    key.setExpireTime(LocalDateTime.parse((String) cachedData.get("expire_time")));
                }
                return key;
            }

            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getApiKey, apiKey);
            ApiKey key = apiKeyMapper.selectOne(wrapper);

            if (key != null) {
                cacheApiKey(key);
            }

            return key;
        } catch (Exception e) {
            logError("获取密钥信息失败", e);
            return null;
        }
    }

    private void clearKeyCache(String apiKey) {
        try {
            String cacheKey = REDIS_KEY_PREFIX + apiKey;
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            logError("清除密钥缓存失败", e);
        }
    }

    private void updateKeyUsageAsync(Long keyId) {
        try {
            String usageKey = "api:key:usage:" + keyId;
            redisTemplate.opsForValue().increment(usageKey, 1);
            redisTemplate.expire(usageKey, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("更新密钥使用统计失败", e);
        }
    }
}
