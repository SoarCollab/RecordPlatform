package cn.flying.config;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.AuthorizeVO;
import cn.flying.filter.JwtAuthenticationFilter;
import cn.flying.filter.OAuth2AuthenticationFilter;
import cn.flying.filter.RequestLogFilter;
import cn.flying.filter.SSOAuthenticationEntryPoint;
import cn.flying.service.AccountService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * @program: RecordPlatform
 * @description: SpringSecurity配置类
 * @author: 王贝强
 * @create: 2025-01-16 11:23
 */
@Configuration
public class SecurityConfiguration {

    @Resource
    JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    RequestLogFilter requestLogFilter;

    @Resource
    JwtUtils utils;

    @Resource
    AccountService service;

    @Resource
    OAuth2AuthenticationFilter oAuth2AuthenticationFilter;

    @Resource
    SSOAuthenticationEntryPoint ssoAuthenticationEntryPoint;

    /**
     * 针对于 SpringSecurity 6 的新版配置方法
     * 支持JWT、OAuth2 Bearer Token和SSO单点登录三种认证方式
     *
     * @param http 配置器
     * @return 自动构建的内置过滤器链
     * @throws Exception 可能的异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(conf -> conf
                        // 监控端点
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/metrics/**").permitAll()
                        .requestMatchers("/trace").permitAll()
                        .requestMatchers("/heapdump").permitAll()
                        // 认证相关端点（JWT和SSO）
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .requestMatchers("/api/oauth2/**").permitAll() // SSO登录和回调端点
                        // 公开资源
                        .requestMatchers("/api/file/download/images/**").permitAll()
                        // API文档
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/doc.html/**", "/webjars/**", "/favicon.ico").permitAll()
                        // 其他请求需要认证和授权
                        .anyRequest().hasAnyRole(Const.ROLE_DEFAULT, Const.ROLE_ADMINISTER, Const.ROLE_MONITOR)
                )
                .formLogin(conf -> conf
                        .loginProcessingUrl("/api/auth/login")
                        .failureHandler(this::handleProcess)
                        .successHandler(this::handleProcess)
                        .permitAll()
                )
                .logout(conf -> conf
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .accessDeniedHandler(this::handleProcess)
                        // 使用SSO认证入口点处理未认证请求
                        .authenticationEntryPoint(ssoAuthenticationEntryPoint)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 过滤器链顺序：RequestLogFilter -> OAuth2AuthenticationFilter -> JwtAuthenticationFilter
                .addFilterBefore(requestLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(oAuth2AuthenticationFilter, cn.flying.filter.RequestLogFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, cn.flying.filter.OAuth2AuthenticationFilter.class)
                .build();
    }

    /**
     * 将多种类型的Handler整合到同一个方法中，包含：
     * - 登录成功
     * - 登录失败
     * - 未登录拦截/无权限拦截
     *
     * @param request                   请求
     * @param response                  响应
     * @param exceptionOrAuthentication 异常或是验证实体
     * @throws IOException 可能的异常
     */
    private void handleProcess(HttpServletRequest request,
                               HttpServletResponse response,
                               Object exceptionOrAuthentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        if (exceptionOrAuthentication instanceof AccessDeniedException exception) {
            writer.write(Result
                    .error(exception.getMessage()).toJson());
        } else if (exceptionOrAuthentication instanceof Exception exception) {
            writer.write(Result
                    .error(exception.getMessage()).toJson());
        } else if (exceptionOrAuthentication instanceof Authentication authentication) {
            User user = (User) authentication.getPrincipal();
            Account account = service.findAccountByNameOrEmail(user.getUsername());

            // 确保使用Long类型的用户ID
            Long userId = account.getId();
            String jwt = utils.createJwt(user, account.getUsername(), userId);

            if (jwt == null) {
                writer.write(Result.error(ResultEnum.PERMISSION_LIMIT).toJson());
            } else {
                AuthorizeVO vo = account.asViewObject(AuthorizeVO.class, o -> o.setToken(jwt));
                vo.setExpire(utils.expireTime());
                writer.write(Result.success(vo).toJson());
            }
        }
    }

    /**
     * 退出登录处理，将对应的Jwt令牌列入黑名单不再使用
     *
     * @param request        请求
     * @param response       响应
     * @param authentication 验证实体
     * @throws IOException 可能的异常
     */
    private void onLogoutSuccess(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        String authorization = request.getHeader("Authorization");
        if (utils.invalidateJwt(authorization)) {
            writer.write(Result.success("退出登录成功").toJson());
            return;
        }
        writer.write(Result.error(ResultEnum.PERMISSION_TOKEN_EXPIRED).toJson());
    }
}
