package cn.flying.identity.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.OAuthClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * OAuth 控制器安全注解校验
 * 确保关键接口均使用 Sa-Token 注解声明权限或登录要求
 */
class OAuthControllerSecurityTest {

    @Test
    void createClientRequiresRegisterPermission() throws NoSuchMethodException {
        Method method = OAuthController.class.getDeclaredMethod("createClient", OAuthClient.class);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        Assertions.assertNotNull(annotation, "createClient 缺少 SaCheckPermission 注解");
        Assertions.assertArrayEquals(new String[]{"oauth:client:register"}, annotation.value(),
                "createClient 权限配置不正确");
    }

    @Test
    void updateClientRequiresUpdatePermission() throws NoSuchMethodException {
        Method method = OAuthController.class.getDeclaredMethod("updateClient", String.class, OAuthClient.class);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        Assertions.assertNotNull(annotation, "updateClient 缺少 SaCheckPermission 注解");
        Assertions.assertArrayEquals(new String[]{"oauth:client:update"}, annotation.value(),
                "updateClient 权限配置不正确");
    }

    @Test
    void deleteClientRequiresDeletePermission() throws NoSuchMethodException {
        Method method = OAuthController.class.getDeclaredMethod("deleteClient", String.class);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        Assertions.assertNotNull(annotation, "deleteClient 缺少 SaCheckPermission 注解");
        Assertions.assertArrayEquals(new String[]{"oauth:client:delete"}, annotation.value(),
                "deleteClient 权限配置不正确");
    }

    @Test
    void getClientRequiresViewPermission() throws NoSuchMethodException {
        Method method = OAuthController.class.getDeclaredMethod("getClient", String.class);
        SaCheckPermission annotation = method.getAnnotation(SaCheckPermission.class);
        Assertions.assertNotNull(annotation, "getClient 缺少 SaCheckPermission 注解");
        Assertions.assertArrayEquals(new String[]{"oauth:client:view"}, annotation.value(),
                "getClient 权限配置不正确");
    }

    @Test
    void getSsoSessionRequiresLogin() throws NoSuchMethodException {
        Method method = OAuthController.class.getDeclaredMethod("getSSOSession",
                String.class, String.class, String.class, String.class);
        SaCheckLogin annotation = method.getAnnotation(SaCheckLogin.class);
        Assertions.assertNotNull(annotation, "getSSOSession 缺少 SaCheckLogin 注解");
    }
}
