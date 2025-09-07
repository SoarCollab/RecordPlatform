package cn.flying.identity.service.impl;

import cn.flying.identity.service.EmailService;
import cn.flying.identity.service.VerifyCodeService;
import cn.flying.identity.util.FlowUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务实现类
 * 提供邮件验证码、短信验证码、图形验证码等功能
 * 
 * @author 王贝强
 */
@Slf4j
@Service
public class VerifyCodeServiceImpl implements VerifyCodeService {

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;
    
    @Resource
    private EmailService emailService;
    
    @Resource
    private FlowUtils flowUtils;
    
    // 验证码配置
    @Value("${verify-code.email.length:6}")
    private int emailCodeLength;
    
    @Value("${verify-code.email.expire-minutes:3}")
    private int emailCodeExpireMinutes;
    
    @Value("${verify-code.email.limit-per-hour:10}")
    private int emailLimitPerHour;
    
    @Value("${verify-code.sms.length:6}")
    private int smsCodeLength;
    
    @Value("${verify-code.sms.expire-minutes:5}")
    private int smsCodeExpireMinutes;
    
    @Value("${verify-code.sms.limit-per-hour:5}")
    private int smsLimitPerHour;
    
    @Value("${verify-code.image.width:130}")
    private int imageWidth;
    
    @Value("${verify-code.image.height:48}")
    private int imageHeight;
    
    @Value("${verify-code.image.expire-minutes:5}")
    private int imageExpireMinutes;
    
    // Redis 键前缀 - 从配置文件获取
    @Value("${redis.prefix.verify.email:verify:email:}")
    private String emailCodePrefix;
    
    @Value("${redis.prefix.verify.sms:verify:sms:}")
    private String smsCodePrefix;
    
    @Value("${redis.prefix.verify.image:verify:image:}")
    private String imageCodePrefix;
    
    @Value("${redis.prefix.verify.limit:verify:limit:}")
    private String sendLimitPrefix;
    
    @Value("${redis.prefix.verify.count:verify:count:}")
    private String sendCountPrefix;

    @Override
    public Result<Void> sendEmailVerifyCode(String email, String type, String clientIp) {
        try {
            // 检查发送频率限制
            Result<Boolean> limitResult = checkSendLimit(email, type, clientIp);
            if (limitResult.getCode() != ResultEnum.SUCCESS.getCode() || !limitResult.getData()) {
                return Result.error(ResultEnum.SYSTEM_BUSY, null);
            }
            
            // 生成验证码
            String code = generateNumericCode(emailCodeLength);
            
            // 发送邮件
            boolean sent = emailService.sendVerifyCode(email, code, type);
            if (!sent) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
            
            // 存储验证码到Redis
            String codeKey = emailCodePrefix + type + ":" + email;
            redisTemplate.opsForValue().set(codeKey, code, emailCodeExpireMinutes, TimeUnit.MINUTES);
            
            // 记录发送次数
            recordSendCount(email, type, clientIp);
            
            log.info("邮件验证码发送成功，邮箱: {}, 类型: {}, IP: {}", email, type, clientIp);
            return Result.success(null);
        } catch (Exception e) {
            log.error("发送邮件验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> verifyEmailCode(String email, String code, String type) {
        try {
            if (StrUtil.isBlank(email) || StrUtil.isBlank(code) || StrUtil.isBlank(type)) {
                return Result.success(false);
            }
            
            String codeKey = emailCodePrefix + type + ":" + email;
            String storedCode = redisTemplate.opsForValue().get(codeKey);
            
            boolean isValid = code.equals(storedCode);
            
            if (isValid) {
                log.info("邮件验证码验证成功，邮箱: {}, 类型: {}", email, type);
            } else {
                log.warn("邮件验证码验证失败，邮箱: {}, 类型: {}, 输入码: {}", email, type, code);
            }
            
            return Result.success(isValid);
        } catch (Exception e) {
            log.error("验证邮件验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> sendSmsVerifyCode(String phone, String type, String clientIp) {
        try {
            // 检查发送频率限制
            Result<Boolean> limitResult = checkSendLimit(phone, type, clientIp);
            if (limitResult.getCode() != 200 || !limitResult.getData()) {
                return Result.error(ResultEnum.SYSTEM_BUSY, null);
            }
            
            // 生成验证码
            String code = generateNumericCode(smsCodeLength);
            
            // TODO: 集成短信服务发送验证码
            // 目前暂时只记录到日志
            log.info("短信验证码: {}, 手机号: {}, 类型: {}", code, phone, type);
            
            // 存储验证码到Redis
            String codeKey = smsCodePrefix + type + ":" + phone;
            redisTemplate.opsForValue().set(codeKey, code, smsCodeExpireMinutes, TimeUnit.MINUTES);
            
            // 记录发送次数
            recordSendCount(phone, type, clientIp);
            
            log.info("短信验证码发送成功，手机号: {}, 类型: {}, IP: {}", phone, type, clientIp);
            return Result.success(null);
        } catch (Exception e) {
            log.error("发送短信验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> verifySmsCode(String phone, String code, String type) {
        try {
            if (StrUtil.isBlank(phone) || StrUtil.isBlank(code) || StrUtil.isBlank(type)) {
                return Result.success(false);
            }
            
            String codeKey = smsCodePrefix + type + ":" + phone;
            String storedCode = redisTemplate.opsForValue().get(codeKey);
            
            boolean isValid = code.equals(storedCode);
            
            if (isValid) {
                log.info("短信验证码验证成功，手机号: {}, 类型: {}", phone, type);
            } else {
                log.warn("短信验证码验证失败，手机号: {}, 类型: {}, 输入码: {}", phone, type, code);
            }
            
            return Result.success(isValid);
        } catch (Exception e) {
            log.error("验证短信验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> generateImageCaptcha(String sessionId) {
        try {
            // 生成图形验证码
            LineCaptcha captcha = CaptchaUtil.createLineCaptcha(imageWidth, imageHeight, 4, 20);
            String code = captcha.getCode();
            String imageBase64 = captcha.getImageBase64();
            
            // 存储验证码到Redis
            String codeKey = imageCodePrefix + sessionId;
            redisTemplate.opsForValue().set(codeKey, code.toLowerCase(), imageExpireMinutes, TimeUnit.MINUTES);
            
            Map<String, Object> result = new HashMap<>();
            result.put("session_id", sessionId);
            result.put("image", "data:image/png;base64," + imageBase64);
            result.put("expire_minutes", imageExpireMinutes);
            
            log.debug("图形验证码生成成功，会话ID: {}", sessionId);
            return Result.success(result);
        } catch (Exception e) {
            log.error("生成图形验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> verifyImageCaptcha(String sessionId, String code) {
        try {
            if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(code)) {
                return Result.success(false);
            }
            
            String codeKey = imageCodePrefix + sessionId;
            String storedCode = redisTemplate.opsForValue().get(codeKey);
            
            boolean isValid = code.toLowerCase().equals(storedCode);
            
            // 验证后删除验证码（一次性使用）
            if (isValid) {
                redisTemplate.delete(codeKey);
                log.debug("图形验证码验证成功，会话ID: {}", sessionId);
            } else {
                log.warn("图形验证码验证失败，会话ID: {}, 输入码: {}", sessionId, code);
            }
            
            return Result.success(isValid);
        } catch (Exception e) {
            log.error("验证图形验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> clearVerifyCode(String identifier, String type) {
        try {
            // 清除邮件验证码
            String emailKey = emailCodePrefix + type + ":" + identifier;
            redisTemplate.delete(emailKey);

            // 清除短信验证码
            String smsKey = smsCodePrefix + type + ":" + identifier;
            redisTemplate.delete(smsKey);

            log.info("验证码清除成功，标识符: {}, 类型: {}", identifier, type);
            return Result.success(null);
        } catch (Exception e) {
            log.error("清除验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getVerifyCodeStats(String identifier, int timeRange) {
        try {
            Map<String, Object> stats = new HashMap<>();

            // 获取发送次数统计
            String countKey = sendCountPrefix + identifier;
            String countStr = redisTemplate.opsForValue().get(countKey);
            int sendCount = countStr != null ? Integer.parseInt(countStr) : 0;

            stats.put("identifier", identifier);
            stats.put("send_count", sendCount);
            stats.put("time_range", timeRange);
            stats.put("max_limit_per_hour", emailLimitPerHour);

            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取验证码统计失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> checkSendLimit(String identifier, String type, String clientIp) {
        try {
            // 检查标识符发送频率
            String identifierLimitKey = sendLimitPrefix + "identifier:" + identifier;
            if (!flowUtils.limitCountCheck(identifierLimitKey, emailLimitPerHour, 3600)) {
                log.warn("标识符 {} 超出每小时发送限制", identifier);
                return Result.success(false);
            }

            // 检查IP发送频率
            String ipLimitKey = sendLimitPrefix + "ip:" + clientIp;
            if (!flowUtils.limitCountCheck(ipLimitKey, emailLimitPerHour * 3, 3600)) {
                log.warn("IP {} 超出每小时发送限制", clientIp);
                return Result.success(false);
            }

            // 检查单次发送间隔（60秒内只能发送一次）
            String onceLimitKey = sendLimitPrefix + "once:" + identifier + ":" + type;
            if (!flowUtils.limitOnceCheck(onceLimitKey, 60)) {
                log.warn("标识符 {} 类型 {} 发送过于频繁", identifier, type);
                return Result.success(false);
            }

            return Result.success(true);
        } catch (Exception e) {
            log.error("检查发送限制失败", e);
            // 异常情况下允许发送，避免影响正常业务
            return Result.success(true);
        }
    }

    @Override
    public Result<Long> getVerifyCodeTtl(String identifier, String type) {
        try {
            // 检查邮件验证码
            String emailKey = emailCodePrefix + type + ":" + identifier;
            Long emailTtl = redisTemplate.getExpire(emailKey, TimeUnit.SECONDS);

            if (emailTtl != null && emailTtl > 0) {
                return Result.success(emailTtl);
            }

            // 检查短信验证码
            String smsKey = smsCodePrefix + type + ":" + identifier;
            Long smsTtl = redisTemplate.getExpire(smsKey, TimeUnit.SECONDS);

            if (smsTtl != null && smsTtl > 0) {
                return Result.success(smsTtl);
            }

            return Result.success(0L);
        } catch (Exception e) {
            log.error("获取验证码TTL失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> cleanExpiredCodes() {
        try {
            Map<String, Object> result = new HashMap<>();
            int cleanedCount = 0;

            // Redis的过期键会自动清理，这里主要是统计信息
            // 实际项目中可以扫描特定模式的键进行清理

            result.put("cleaned_count", cleanedCount);
            result.put("clean_time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

            return Result.success(result);
        } catch (Exception e) {
            log.error("清理过期验证码失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Map<String, Object>> getVerifyCodeConfig() {
        Map<String, Object> config = new HashMap<>();

        // 邮件验证码配置
        Map<String, Object> emailConfig = new HashMap<>();
        emailConfig.put("length", emailCodeLength);
        emailConfig.put("expire_minutes", emailCodeExpireMinutes);
        emailConfig.put("limit_per_hour", emailLimitPerHour);
        config.put("email", emailConfig);

        // 短信验证码配置
        Map<String, Object> smsConfig = new HashMap<>();
        smsConfig.put("length", smsCodeLength);
        smsConfig.put("expire_minutes", smsCodeExpireMinutes);
        smsConfig.put("limit_per_hour", smsLimitPerHour);
        config.put("sms", smsConfig);

        // 图形验证码配置
        Map<String, Object> imageConfig = new HashMap<>();
        imageConfig.put("width", imageWidth);
        imageConfig.put("height", imageHeight);
        imageConfig.put("expire_minutes", imageExpireMinutes);
        config.put("image", imageConfig);

        return Result.success(config);
    }

    /**
     * 生成数字验证码
     */
    private String generateNumericCode(int length) {
        return RandomUtil.randomNumbers(length);
    }

    /**
     * 记录发送次数
     */
    private void recordSendCount(String identifier, String type, String clientIp) {
        try {
            String countKey = sendCountPrefix + identifier;
            redisTemplate.opsForValue().increment(countKey);
            redisTemplate.expire(countKey, 1, TimeUnit.HOURS);

            log.debug("记录验证码发送次数，标识符: {}, 类型: {}, IP: {}", identifier, type, clientIp);
        } catch (Exception e) {
            log.error("记录发送次数失败", e);
        }
    }
}
