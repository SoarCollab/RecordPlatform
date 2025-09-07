package cn.flying.identity.service.impl;

import at.favre.lib.crypto.bcrypt.BCrypt;
import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.service.PasswordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 密码加密服务实现类
 * 使用 BCrypt 算法进行密码加密，替代 Spring Security 的 PasswordEncoder
 * 
 * @author 王贝强
 */
@Slf4j
@Service
public class PasswordServiceImpl implements PasswordService {
    
    @Autowired
    private ApplicationProperties applicationProperties;
    
    /**
     * 加密密码
     * 
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    @Override
    public String encode(String rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("密码不能为空");
        }

        try {
            int strength = applicationProperties.getPassword().getStrength();
            return BCrypt.withDefaults().hashToString(strength, rawPassword.toCharArray());
        } catch (Exception e) {
            log.error("密码加密失败", e);
            throw new RuntimeException("密码加密失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 验证密码
     * 
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(rawPassword.toCharArray(), encodedPassword);
            return result.verified;
        } catch (Exception e) {
            log.error("密码验证失败", e);
            return false;
        }
    }
}
