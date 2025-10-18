package cn.flying.identity.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.interceptor.SaTokenAuthInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token配置类
 * 配置Sa-Token的拦截器和路由规则
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Resource
    private SaTokenAuthInterceptor saTokenAuthInterceptor;

    // Sa-Token 整合 jwt (Stateless 无状态模式)
    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForStateless();
    }

    /**
     * 注册Sa-Token拦截器
     * 配置哪些路径需要进行权限验证
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册Sa-Token拦截器，校验规则为StpUtil.checkLogin()登录校验
        registry.addInterceptor(new SaInterceptor(handle -> {
            // 指定一条match规则
            SaRouter
                    // 拦截所有路径
                    .match("/**")
                    // 排除登录、注册、验证码、OAuth 令牌等公开接口（RESTful新版路径）
                    .notMatch("/api/auth/sessions")
                    .notMatch("/api/auth/users")
                    .notMatch("/api/auth/verification-codes")
                    .notMatch("/api/auth/passwords/reset")
                    .notMatch("/api/auth/sessions/status")
                    .notMatch("/api/verification/**")
                    .notMatch("/api/oauth/tokens")
                    .notMatch("/api/oauth/tokens/revoke")
                    .notMatch("/api/oauth/users/me")
                    .notMatch("/api/oauth/authorizations")
                    .notMatch("/api/oauth/sso/sessions")
                    .notMatch("/api/auth/third-party/**")
                    // 排除Swagger文档相关路径
                    .notMatch("/doc.html")
                    .notMatch("/swagger-ui/**")
                    .notMatch("/swagger-resources/**")
                    .notMatch("/v3/api-docs/**")
                    .notMatch("/webjars/**")
                    // 排除Druid监控页面
                    .notMatch("/druid/**")
                    // 排除静态资源
                    .notMatch("/static/**")
                    .notMatch("/favicon.ico")
                    // 排除文档路径
                    .notMatch("/docs/**")
                    // 排除健康检查
                    .notMatch("/actuator/**")
                    // 执行认证函数
                    .check(r -> StpUtil.checkLogin());
        })).addPathPatterns("/**");

        // 注册自定义的认证拦截器，用于设置用户上下文信息
        registry.addInterceptor(saTokenAuthInterceptor)
                .addPathPatterns("/**")
                .order(1); // 设置较高优先级，在 SA-Token 拦截器之后执行
    }
}