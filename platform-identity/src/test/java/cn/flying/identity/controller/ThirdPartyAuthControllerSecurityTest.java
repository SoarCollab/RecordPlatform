package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.flying.identity.dto.BindAccountRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * 第三方登录控制器安全注解校验
 */
class ThirdPartyAuthControllerSecurityTest {

    @Test
    void bindAccountRequiresLogin() throws NoSuchMethodException {
        Method method = ThirdPartyAuthController.class.getDeclaredMethod("bindAccount", String.class, BindAccountRequest.class);
        Assertions.assertNotNull(method.getAnnotation(SaCheckLogin.class), "bindAccount 缺少 SaCheckLogin 注解");
    }

    @Test
    void unbindAccountRequiresLogin() throws NoSuchMethodException {
        Method method = ThirdPartyAuthController.class.getDeclaredMethod("unbindAccount", String.class);
        Assertions.assertNotNull(method.getAnnotation(SaCheckLogin.class), "unbindAccount 缺少 SaCheckLogin 注解");
    }

    @Test
    void getBindingsRequiresLogin() throws NoSuchMethodException {
        Method method = ThirdPartyAuthController.class.getDeclaredMethod("getBindings");
        Assertions.assertNotNull(method.getAnnotation(SaCheckLogin.class), "getBindings 缺少 SaCheckLogin 注解");
    }

    @Test
    void refreshTokenRequiresLogin() throws NoSuchMethodException {
        Method method = ThirdPartyAuthController.class.getDeclaredMethod("refreshToken", String.class);
        Assertions.assertNotNull(method.getAnnotation(SaCheckLogin.class), "refreshToken 缺少 SaCheckLogin 注解");
    }
}
