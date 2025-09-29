package cn.flying.identity.validation;

import cn.flying.identity.config.ApplicationProperties;
import jakarta.annotation.Resource;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.stereotype.Component;

/**
 * 密码验证器
 * 根据配置文件中的密码长度限制进行验证
 *
 * @author 王贝强
 */
@Component
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Resource
    private ApplicationProperties applicationProperties;

    @Override
    public void initialize(ValidPassword constraintAnnotation) {
        // 初始化方法，可以在这里处理注解参数
    }

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }

        int minLength = applicationProperties.getPassword().getMinLength();
        int maxLength = applicationProperties.getPassword().getMaxLength();

        if (password.length() < minLength || password.length() > maxLength) {
            // 禁用默认消息并设置自定义消息
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                String.format("密码长度必须在%d-%d个字符之间", minLength, maxLength)
            ).addConstraintViolation();
            return false;
        }

        return true;
    }
}