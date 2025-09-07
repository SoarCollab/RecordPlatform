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

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 账户服务实现类
 * 从 platform-backend 迁移而来，适配 SA-Token 框架
 *
 * @author 王贝强
 */
@Slf4j
@Service
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {

    @Resource
    private ApplicationProperties applicationProperties;

    @Resource
    private EmailService emailService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PasswordService passwordService;

    @Resource
    private FlowUtils flowUtils;

    @Override
    public Account findAccountByNameOrEmail(String text) {
        return this.query()
                .eq("username", text).or()
                .eq("email", text)
                .last("limit 1")
                .one();
    }

    @Override
    public Account findAccountById(Long id) {
        return this.query().eq("id", id).one();
    }

    @Override
    public Result<Void> registerEmailVerifyCode(String type, String email, String address) {
        synchronized (address.intern()) {
            int verifyLimit = applicationProperties.getVerifyCode().getEmailLimit();
            if (!flowUtils.checkEmailVerifyLimit(address, verifyLimit)) {
                return Result.error(ResultEnum.PARAM_IS_INVALID, null);
            }

            Random random = new Random();
            int code = random.nextInt(899999) + 100000;
            String codeStr = String.valueOf(code);

            // 发送邮件
            boolean sent = emailService.sendVerifyCode(email, codeStr, type);
            if (!sent) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 存储验证码到Redis
            stringRedisTemplate.opsForValue()
                    .set(Const.VERIFY_EMAIL_DATA + email, codeStr, 3, TimeUnit.MINUTES);

            return Result.success(null);
        }
    }

    @Override
    public Result<Void> registerEmailAccount(EmailRegisterVO info) {
        try {
            String email = info.getEmail();
            String code = getEmailVerifyCode(email);

            if (code == null) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (!code.equals(info.getCode())) {
                return Result.error(ResultEnum.AUTH_CODE_ERROR, null);
            }
            if (existsAccountByEmail(email)) {
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            String username = info.getUsername();
            if (existsAccountByUsername(username)) {
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            String password = encodePassword(info.getPassword());
            Account account = new Account(IdUtils.nextUserId(), info.getUsername(),
                    password, email, Const.ROLE_DEFAULT, null);

            if (!this.save(account)) {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            } else {
                deleteEmailVerifyCode(email);
                return Result.success(null);
            }
        } catch (Exception e) {
            log.error("注册账户失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
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
                return Result.success(null);
            } else {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("重置密码失败", e);
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
            Account account = findAccountByNameOrEmail(email);
            if (account != null && !Objects.equals(account.getId(), userId)) {
                return Result.error(ResultEnum.USER_HAS_EXISTED, null);
            }

            boolean update = this.update().eq("id", userId).set("email", email).update();
            if (update) {
                return Result.success(null);
            } else {
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }
        } catch (Exception e) {
            log.error("修改邮箱失败", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
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

            return success ? Result.success(null) : Result.error(ResultEnum.SYSTEM_ERROR, null);
        } catch (Exception e) {
            log.error("修改密码失败", e);
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
}
