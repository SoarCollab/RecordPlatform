package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiKeyService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
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
 * API密钥管理服务实现类
 * 提供密钥生成、验证、轮换等核心功能
 * <p>
 * 安全特性:
 * 1. ApiSecret使用AES-256加密存储
 * 2. 签名验证使用HMAC-SHA256算法
 * 3. 支持时间戳和Nonce防重放攻击
 * 4. 密钥使用次数统计和过期管理
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiKeyServiceImpl extends BaseService implements ApiKeyService {

    /**
     * Redis键前缀
     */
    private static final String REDIS_KEY_PREFIX = "api:key:";
    private static final String NONCE_PREFIX = "api:nonce:";

    @Resource
    private ApiKeyMapper apiKeyMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * AES加密密钥(从配置文件读取)
     */
    @Value("${api.key.aes-secret:record-platform-api-key-secret-2025}")
    private String aesSecretKey;

    /**
     * 防重放攻击时间窗口(秒)
     */
    @Value("${api.key.replay-window:300}")
    private long replayWindow;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> generateApiKey(Long appId, String keyName, Integer keyType, Integer expireDays) {
        return safeExecuteData(() -> {
            // 参数验证
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(keyType, "密钥类型不能为空");
            requireCondition(keyType, type -> type == 1 || type == 2, "密钥类型必须是1(正式)或2(测试)");

            // 生成ApiKey和ApiSecret
            String apiKey = "ak_" + RandomUtil.randomString(32);
            String apiSecret = "sk_" + RandomUtil.randomString(48);

            // 加密存储ApiSecret
            String encryptedSecret = encryptSecret(apiSecret);

            // 构建密钥实体
            ApiKey key = new ApiKey();
            key.setId(IdUtils.nextEntityId());
            key.setAppId(appId);
            key.setApiKey(apiKey);
            key.setApiSecret(encryptedSecret);
            key.setKeyName(getOrElse(keyName, "默认密钥"));
            key.setKeyStatus(1); // 默认启用
            key.setKeyType(keyType);
            key.setUsedCount(0L);

            // 设置过期时间
            if (expireDays != null && expireDays > 0) {
                key.setExpireTime(LocalDateTime.now().plusDays(expireDays));
            }

            // 保存到数据库
            int inserted = apiKeyMapper.insert(key);
            requireCondition(inserted, count -> count > 0, "生成密钥失败");

            // 缓存密钥信息到Redis(用于快速验证)
            cacheApiKey(key);

            // 构建返回结果(注意:ApiSecret明文只在生成时返回一次)
            Map<String, Object> result = new HashMap<>();
            result.put("key_id", key.getId());
            result.put("app_id", appId);
            result.put("api_key", apiKey);
            result.put("api_secret", apiSecret); // 明文返回,之后不再提供
            result.put("key_name", key.getKeyName());
            result.put("key_type", keyType);
            result.put("expire_time", key.getExpireTime());
            result.put("create_time", key.getCreateTime());

            logInfo("生成API密钥成功: appId={}, keyId={}, keyType={}", appId, key.getId(), keyType);
            return result;
        }, "生成API密钥失败");
    }

    @Override
    public Result<Map<String, Object>> validateApiKey(String apiKey, Long timestamp, String nonce,
                                                      String signature, String requestData) {
        try {
            // 参数验证
            if (isBlank(apiKey) || timestamp == null || isBlank(nonce) || isBlank(signature)) {
                logWarn("API密钥验证失败: 参数不完整");
                return error("参数不完整");
            }

            // 1. 检查时间戳(防重放攻击)
            long currentTime = System.currentTimeMillis() / 1000;
            if (Math.abs(currentTime - timestamp) > replayWindow) {
                logWarn("API密钥验证失败: 请求时间戳超出允许范围, apiKey={}", apiKey);
                return error("请求已过期");
            }

            // 2. 检查Nonce是否已使用(防重放攻击)
            String nonceKey = NONCE_PREFIX + nonce;
            Boolean nonceExists = redisTemplate.hasKey(nonceKey);
            if (Boolean.TRUE.equals(nonceExists)) {
                logWarn("API密钥验证失败: Nonce已被使用, nonce={}", nonce);
                return error("Nonce已被使用");
            }

            // 3. 从缓存或数据库查询密钥信息
            ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
            if (keyInfo == null) {
                logWarn("API密钥验证失败: 密钥不存在, apiKey={}", apiKey);
                return error("API密钥无效");
            }

            // 4. 检查密钥状态
            if (keyInfo.getKeyStatus() != 1) {
                logWarn("API密钥验证失败: 密钥已禁用, apiKey={}, status={}", apiKey, keyInfo.getKeyStatus());
                return error("API密钥已禁用");
            }

            // 5. 检查密钥是否过期
            if (keyInfo.getExpireTime() != null && LocalDateTime.now().isAfter(keyInfo.getExpireTime())) {
                logWarn("API密钥验证失败: 密钥已过期, apiKey={}", apiKey);
                return error("API密钥已过期");
            }

            // 6. 解密ApiSecret并验证签名
            String decryptedSecret = decryptSecret(keyInfo.getApiSecret());
            String expectedSignature = generateSignature(apiKey, decryptedSecret, timestamp, nonce, requestData);

            if (!expectedSignature.equals(signature)) {
                logWarn("API密钥验证失败: 签名不匹配, apiKey={}", apiKey);
                return error("签名无效");
            }

            // 7. 标记Nonce已使用(设置过期时间为时间窗口的2倍)
            redisTemplate.opsForValue().set(nonceKey, "1", replayWindow * 2, TimeUnit.SECONDS);

            // 8. 异步更新密钥使用统计
            updateKeyUsageAsync(keyInfo.getId());

            // 9. 返回验证结果
            Map<String, Object> result = new HashMap<>();
            result.put("app_id", keyInfo.getAppId());
            result.put("key_id", keyInfo.getId());
            result.put("key_name", keyInfo.getKeyName());
            result.put("key_type", keyInfo.getKeyType());

            logDebug("API密钥验证成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
            return success(result);
        } catch (Exception e) {
            logError("API密钥验证异常", e);
            return error("系统错误");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> enableKey(Long keyId) {
        return safeExecuteAction(() -> {
            requireNonNull(keyId, "密钥ID不能为空");

            ApiKey key = apiKeyMapper.selectById(keyId);
            requireNonNull(key, "密钥不存在");

            key.setKeyStatus(1);
            int updated = apiKeyMapper.updateById(key);
            requireCondition(updated, count -> count > 0, "启用密钥失败");

            // 更新缓存
            cacheApiKey(key);

            logInfo("启用API密钥成功: keyId={}", keyId);
        }, "启用密钥失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> disableKey(Long keyId) {
        return safeExecuteAction(() -> {
            requireNonNull(keyId, "密钥ID不能为空");

            ApiKey key = apiKeyMapper.selectById(keyId);
            requireNonNull(key, "密钥不存在");

            key.setKeyStatus(0);
            int updated = apiKeyMapper.updateById(key);
            requireCondition(updated, count -> count > 0, "禁用密钥失败");

            // 清除缓存
            clearKeyCache(key.getApiKey());

            logInfo("禁用API密钥成功: keyId={}", keyId);
        }, "禁用密钥失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteKey(Long keyId) {
        return safeExecuteAction(() -> {
            requireNonNull(keyId, "密钥ID不能为空");

            ApiKey key = apiKeyMapper.selectById(keyId);
            requireNonNull(key, "密钥不存在");

            int deleted = apiKeyMapper.deleteById(keyId);
            requireCondition(deleted, count -> count > 0, "删除密钥失败");

            // 清除缓存
            clearKeyCache(key.getApiKey());

            logInfo("删除API密钥成功: keyId={}", keyId);
        }, "删除密钥失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> rotateKey(Long oldKeyId) {
        return safeExecuteData(() -> {
            requireNonNull(oldKeyId, "旧密钥ID不能为空");

            // 查询旧密钥
            ApiKey oldKey = apiKeyMapper.selectById(oldKeyId);
            requireNonNull(oldKey, "旧密钥不存在");

            // 生成新密钥
            Result<Map<String, Object>> newKeyResult = generateApiKey(
                    oldKey.getAppId(),
                    oldKey.getKeyName() + "_rotated",
                    oldKey.getKeyType(),
                    null // 新密钥默认永久有效
            );

            if (!isSuccess(newKeyResult)) {
                throw new RuntimeException("生成新密钥失败");
            }

            // 禁用旧密钥
            oldKey.setKeyStatus(0);
            apiKeyMapper.updateById(oldKey);
            clearKeyCache(oldKey.getApiKey());

            Map<String, Object> result = newKeyResult.getData();
            result.put("old_key_id", oldKeyId);
            result.put("rotated_at", LocalDateTime.now());

            logInfo("轮换API密钥成功: oldKeyId={}, newKeyId={}", oldKeyId, result.get("key_id"));
            return result;
        }, "轮换密钥失败");
    }

    @Override
    public Result<List<ApiKey>> getKeysByAppId(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getAppId, appId)
                    .orderByDesc(ApiKey::getCreateTime);

            List<ApiKey> keys = apiKeyMapper.selectList(wrapper);
            return keys != null ? keys : new ArrayList<>();
        }, "查询密钥列表失败");
    }

    @Override
    public Result<ApiKey> getKeyById(Long keyId) {
        return safeExecuteData(() -> {
            requireNonNull(keyId, "密钥ID不能为空");
            ApiKey key = apiKeyMapper.selectById(keyId);
            requireNonNull(key, "密钥不存在");
            return key;
        }, "查询密钥详情失败");
    }

    @Override
    public Result<Void> updateLastUsedTime(Long keyId) {
        return safeExecuteAction(() -> {
            requireNonNull(keyId, "密钥ID不能为空");

            ApiKey key = new ApiKey();
            key.setId(keyId);
            key.setLastUsedTime(LocalDateTime.now());

            int updated = apiKeyMapper.updateById(key);
            requireCondition(updated, count -> count > 0, "更新最后使用时间失败");
        }, "更新最后使用时间失败");
    }

    @Override
    public Result<List<ApiKey>> getExpiringKeys(int days) {
        return safeExecuteData(() -> {
            LocalDateTime threshold = LocalDateTime.now().plusDays(days);

            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getKeyStatus, 1) // 仅查询启用状态
                    .isNotNull(ApiKey::getExpireTime)
                    .le(ApiKey::getExpireTime, threshold);

            List<ApiKey> keys = apiKeyMapper.selectList(wrapper);
            return keys != null ? keys : new ArrayList<>();
        }, "查询即将过期密钥失败");
    }

    @Override
    public Result<Void> validateApiKey(String apiKey) {
        try {
            // 参数验证
            if (isBlank(apiKey)) {
                logWarn("API密钥验证失败: 密钥为空");
                return error("API密钥不能为空");
            }

            // 从缓存或数据库查询密钥信息
            ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
            if (keyInfo == null) {
                logWarn("API密钥验证失败: 密钥不存在, apiKey={}", apiKey);
                return error("API密钥无效");
            }

            // 检查密钥状态
            if (keyInfo.getKeyStatus() != 1) {
                logWarn("API密钥验证失败: 密钥已禁用, apiKey={}, status={}", apiKey, keyInfo.getKeyStatus());
                return error("API密钥已禁用");
            }

            // 检查密钥是否过期
            if (keyInfo.getExpireTime() != null && LocalDateTime.now().isAfter(keyInfo.getExpireTime())) {
                logWarn("API密钥验证失败: 密钥已过期, apiKey={}", apiKey);
                return error("API密钥已过期");
            }

            // 异步更新密钥使用统计
            updateKeyUsageAsync(keyInfo.getId());

            logDebug("API密钥简化验证成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
            return success();
        } catch (Exception e) {
            logError("API密钥简化验证异常", e);
            return error("系统错误");
        }
    }

    @Override
    public Result<ApiKey> getKeyInfoByApiKey(String apiKey) {
        return safeExecuteData(() -> {
            requireNonBlank(apiKey, "API密钥不能为空");

            // 从缓存或数据库查询密钥信息
            ApiKey keyInfo = getKeyFromCacheOrDb(apiKey);
            requireNonNull(keyInfo, "API密钥不存在");

            // 检查密钥状态
            if (keyInfo.getKeyStatus() != 1) {
                throw new RuntimeException("API密钥已禁用");
            }

            // 检查密钥是否过期
            if (keyInfo.getExpireTime() != null && LocalDateTime.now().isAfter(keyInfo.getExpireTime())) {
                throw new RuntimeException("API密钥已过期");
            }

            // 异步更新密钥使用统计
            updateKeyUsageAsync(keyInfo.getId());

            logDebug("获取API密钥信息成功: appId={}, keyId={}", keyInfo.getAppId(), keyInfo.getId());
            return keyInfo;
        }, "获取密钥信息失败");
    }

    /**
     * 加密ApiSecret
     * 使用AES-256-GCM算法加密，提供认证加密和数据完整性验证
     *
     * @param secret 明文密钥
     * @return 加密后的密文（Base64编码，包含IV）
     */
    private String encryptSecret(String secret) {
        try {
            // 确保密钥长度为32字节(256位)
            byte[] keyBytes = aesSecretKey.getBytes(StandardCharsets.UTF_8);
            keyBytes = Arrays.copyOf(keyBytes, 32);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            // 生成随机IV（GCM推荐12字节）
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv); // 128位认证标签

            // 初始化加密器
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // 执行加密
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            // 组合IV和密文，然后Base64编码
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("加密密钥失败", e);
        }
    }

    /**
     * 解密ApiSecret
     * 使用AES-256-GCM算法解密
     *
     * @param encryptedSecret 加密的密文（Base64编码，包含IV）
     * @return 解密后的明文
     */
    private String decryptSecret(String encryptedSecret) {
        try {
            // 确保密钥长度为32字节(256位)
            byte[] keyBytes = aesSecretKey.getBytes(StandardCharsets.UTF_8);
            keyBytes = Arrays.copyOf(keyBytes, 32);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

            // Base64解码
            byte[] combined = Base64.getDecoder().decode(encryptedSecret);

            // 提取IV（前12字节）和密文
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);

            // 初始化解密器
            GCMParameterSpec parameterSpec = new GCMParameterSpec(128, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // 执行解密
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密密钥失败", e);
        }
    }

    /**
     * 生成签名
     * 使用HMAC-SHA256算法生成签名，改进版本包含分隔符防止碰撞攻击
     *
     * @param apiKey      API密钥
     * @param apiSecret   API密钥密文
     * @param timestamp   时间戳
     * @param nonce       随机字符串
     * @param requestData 请求数据
     * @return 签名字符串
     */
    private String generateSignature(String apiKey, String apiSecret, Long timestamp,
                                     String nonce, String requestData) {
        // 使用标准的签名格式，添加分隔符防止碰撞攻击
        StringBuilder signBuilder = new StringBuilder();
        signBuilder.append(apiKey).append("\n");
        signBuilder.append(timestamp).append("\n");
        signBuilder.append(nonce).append("\n");

        // 对请求数据进行哈希，确保签名的一致性
        if (requestData != null && !requestData.isEmpty()) {
            // 对请求数据先进行SHA256哈希，避免大数据影响性能
            String dataHash = SecureUtil.sha256(requestData);
            signBuilder.append(dataHash);
        } else {
            signBuilder.append("");
        }

        String signData = signBuilder.toString();

        // 使用HMAC-SHA256算法签名
        return SecureUtil.hmacSha256(apiSecret).digestHex(signData);
    }

    /**
     * 缓存密钥信息到Redis
     *
     * @param key 密钥实体
     */
    private void cacheApiKey(ApiKey key) {
        try {
            String cacheKey = REDIS_KEY_PREFIX + key.getApiKey();
            Map<String, String> keyInfo = new HashMap<>();
            keyInfo.put("id", key.getId().toString());
            keyInfo.put("app_id", key.getAppId().toString());
            keyInfo.put("api_secret", key.getApiSecret());
            keyInfo.put("key_status", key.getKeyStatus().toString());
            keyInfo.put("key_name", key.getKeyName());
            keyInfo.put("key_type", key.getKeyType().toString());
            if (key.getExpireTime() != null) {
                keyInfo.put("expire_time", key.getExpireTime().toString());
            }

            redisTemplate.opsForHash().putAll(cacheKey, keyInfo);
            // 设置缓存过期时间为24小时
            redisTemplate.expire(cacheKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("缓存密钥信息失败", e);
        }
    }

    /**
     * 从缓存或数据库获取密钥信息
     *
     * @param apiKey API密钥
     * @return 密钥实体
     */
    private ApiKey getKeyFromCacheOrDb(String apiKey) {
        try {
            // 先从缓存获取
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

            // 缓存未命中,从数据库查询
            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getApiKey, apiKey);
            ApiKey key = apiKeyMapper.selectOne(wrapper);

            if (key != null) {
                // 缓存查询结果
                cacheApiKey(key);
            }

            return key;
        } catch (Exception e) {
            logError("获取密钥信息失败", e);
            return null;
        }
    }

    /**
     * 清除密钥缓存
     *
     * @param apiKey API密钥
     */
    private void clearKeyCache(String apiKey) {
        try {
            String cacheKey = REDIS_KEY_PREFIX + apiKey;
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            logError("清除密钥缓存失败", e);
        }
    }

    /**
     * 异步更新密钥使用统计
     *
     * @param keyId 密钥ID
     */
    private void updateKeyUsageAsync(Long keyId) {
        // 使用Redis计数器实现异步统计,避免频繁更新数据库
        try {
            String usageKey = "api:key:usage:" + keyId;
            redisTemplate.opsForValue().increment(usageKey, 1);
            // 设置过期时间为1小时
            redisTemplate.expire(usageKey, 1, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("更新密钥使用统计失败", e);
        }
    }
}
