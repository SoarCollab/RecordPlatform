package cn.flying.identity.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/**
 * 密码验证注解
 * 使用配置文件中的密码长度限制进行验证
 *
 * @author 王贝强
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {
    String message() default "密码长度不符合要求";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}