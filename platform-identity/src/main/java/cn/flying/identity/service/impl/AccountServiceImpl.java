package cn.flying.identity.service.impl;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.constant.Const;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.AccountService;
import cn.flying.identity.service.EmailService;
import cn.flying.identity.service.PasswordService;
import cn.flying.identity.util.FlowUtils;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.vo.request.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 账户服务实现类
 * 从 platform-backend 迁移而来，适配 SA-Token 框架
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    // 使用SecureRandom生成安全的随机数
    private static final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private ApplicationProperties applicationProperties;

    @Resource
    private EmailService emailService;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PasswordService passwordService;

    @Resource
    private FlowUtils flowUtils;

    private final ConcurrentHashMap<String, ReentrantLock> verifyCodeLocks = new ConcurrentHashMap<>();

    private static final String UNKNOWN_IP = "unknown";

    @Override
    public Account findAccountByNameOrEmail(String text) {
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .one();
    }

    @Override
    public Account findAccountById(Long id) {
        return this.query().eq("id", id).one();
    }

    @Override
    public Result<Void> registerEmailVerifyCode(String type, String email, String address) {
        String clientIp = (address == null || address.isBlank()) ? UNKNOWN_IP : address.trim();
        ReentrantLock lock = verifyCodeLocks.computeIfAbsent(clientIp, key -> new ReentrantLock());
        lock.lock();
        try {
            int verifyLimit = applicationProperties.getVerifyCode().getEmailLimit();
            if (!flowUtils.checkEmailVerifyLimit(clientIp, verifyLimit)) {
                log.warn("验证码请求过于频繁 - IP: {}", maskIp(clientIp));
                return Result.errorWithMessage(ResultEnum.PERMISSION_LIMIT, "请求过于频繁，请稍后再试");
            }

            int length = applicationProperties.getVerifyCode().getLength();
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(secureRandom.nextInt(10));
            }
            String codeStr = sb.toString();

            boolean sent = emailService.sendVerifyCode(email, codeStr, type);
            if (!sent) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            long expireMinutes = applicationProperties.getVerifyCode().getExpireMinutes();
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, codeStr, expireMinutes, TimeUnit.MINUTES);

            return Result.success(null);
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                verifyCodeLocks.remove(clientIp, lock);
            }
        }
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> registerEmailAccount(EmailRegisterVO info) {
        try {
            String email = info.getEmail();
            String code = getEmailVerifyCode(email);

            if (code == null) {
                log.warn("注册失败 - 验证码不存在: email={}", maskEmail(email));
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (!code.equals(info.getCode())) {
                log.warn("注册失败 - 验证码错误: email={}", maskEmail(email));
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (existsAccountByEmail(email)) {
                log.warn("注册失败 - 邮箱已存在: email={}", maskEmail(email));
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            String username = info.getUsername();
            if (existsAccountByUsername(username)) {
                log.warn("注册失败 - 用户名已存在: username={}", maskUsername(username));
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            String password = encodePassword(info.getPassword());
            Account account = new Account(IdUtils.nextUserId(), info.getUsername(),
                    password, email, Const.ROLE_DEFAULT, null);

            if (!this.save(account)) {
                log.error("注册失败 - 数据库保存失败: username={}", maskUsername(username));
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            } else {
                deleteEmailVerifyCode(email);
                log.info("用户注册成功: username={}, email={}", maskUsername(username), maskEmail(email));
                return Result.success(null);
            }
        } catch (Exception e) {
            log.error("注册账户失败", e);
            markRollbackIfActive();
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> resetEmailAccountPassword(EmailResetVO info) {
        try {
            String email = info.getEmail();
            String code = getEmailVerifyCode(email);

            if (code == null) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (!code.equals(info.getCode())) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }

            String password = encodePassword(info.getPassword());
            boolean success = this.update()
                    .eq("email", email)
                    .set("password", password)
                    .update();

            if (success) {
                deleteEmailVerifyCode(email);
                log.info("密码重置成功: email={}", maskEmail(email));
                return Result.success(null);
            } else {
                log.error("密码重置失败 - 数据库更新失败: email={}", maskEmail(email));
                markRollbackIfActive();
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("重置密码失败", e);
            markRollbackIfActive();
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Void> resetConfirm(ConfirmResetVO info) {
        try {
            String email = info.getEmail();
            String code = getEmailVerifyCode(email);

            if (code == null) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (!code.equals(info.getCode())) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }

            // 验证码正确，可以进行密码重置
            return Result.success(null);
        } catch (Exception e) {
            log.error("确认重置失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> modifyEmail(Long userId, ModifyEmailVO modifyEmailVO) {
        try {
            String email = modifyEmailVO.getEmail();
            String code = getEmailVerifyCode(email);

            if (code == null) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (!code.equals(modifyEmailVO.getCode())) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }

            deleteEmailVerifyCode(email);

            Account account = this.query().eq("email", email).one();
            if (account != null && !Objects.equals(account.getId(), userId)) {
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            boolean update = this.update().eq("id", userId).set("email", email).update();
            if (update) {
                log.info("邮箱修改成功: userId={}, newEmail={}", userId, maskEmail(email));
                return Result.success(null);
            } else {
                log.error("邮箱修改失败 - 数据库更新失败: userId={}", userId);
                markRollbackIfActive();
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("修改邮箱失败", e);
            markRollbackIfActive();
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> changePassword(Long userId, ChangePasswordVO changePasswordVO) {
        try {
            Account account = this.query().eq("id", userId).one();
            if (account == null) {
                return Result.error(ResultEnum.USER_NOT_EXIST, null);
            }

            if (!matchesPassword(changePasswordVO.getPassword(), account.getPassword())) {
                return Result.error(ResultEnum.USER_LOGIN_ERROR, null);
            }

            boolean success = this.update().eq("id", userId)
                    .set("password", encodePassword(changePasswordVO.getNewPassword()))
                    .update();

            if (success) {
                log.info("密码修改成功: userId={}", userId);
                return Result.success(null);
            } else {
                log.error("密码修改失败 - 数据库更新失败: userId={}", userId);
                markRollbackIfActive();
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("修改密码失败", e);
            markRollbackIfActive();
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }


    @Override
    public boolean existsAccountByEmail(String email) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("email", email));
    }

    @Override
    public boolean existsAccountByUsername(String username) {
        return this.baseMapper.exists(Wrappers.<Account>query().eq("username", username));
    }

    @Override
    public boolean matchesPassword(String rawPassword, String encodedPassword) {
        return passwordService.matches(rawPassword, encodedPassword);
    }

    @Override
    public String encodePassword(String rawPassword) {
        return passwordService.encode(rawPassword);
    }

    /**
     * 移除Redis中存储的邮件验证码
     *
     * @param email 邮箱
     */
    private void deleteEmailVerifyCode(String email) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        stringRedisTemplate.delete(key);
    }

    /**
     * 获取Redis中存储的邮件验证码
     *
     * @param email 邮箱
     * @return 验证码
     */
    private String getEmailVerifyCode(String email) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        return stringRedisTemplate.opsForValue().get(key);
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return email.length() <= 3 ? email.charAt(0) + "***" : email.substring(0, 3) + "***";
        }
        String prefix = email.substring(0, Math.min(3, atIndex));
        String domain = email.substring(atIndex);
        return prefix + "***" + domain;
    }

    private String maskUsername(String username) {
        if (username == null || username.isBlank()) {
            return "***";
        }
        if (username.length() <= 2) {
            return username.charAt(0) + "*";
        }
        return username.charAt(0) + "***" + username.substring(username.length() - 1);
    }

    private String maskIp(String ip) {
        if (ip == null || ip.isBlank() || UNKNOWN_IP.equals(ip)) {
            return "***";
        }
        if (ip.contains(".")) {
            String[] parts = ip.split("\\.");
            if (parts.length == 4) {
                parts[3] = "*";
                return String.join(".", parts);
            }
        }
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            if (parts.length > 2) {
                return parts[0] + ":" + parts[1] + ":***";
            }
        }
        return ip.substring(0, Math.min(3, ip.length())) + "***";
    }

    /**
     * 在当前方法处于事务中时标记回滚
     */
    private void markRollbackIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (Exception ignored) {
            // 无活动事务时忽略
        }
    }
}
