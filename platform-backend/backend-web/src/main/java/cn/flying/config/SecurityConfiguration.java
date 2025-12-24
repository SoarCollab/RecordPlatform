package cn.flying.config;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.AuthorizeVO;
import cn.flying.filter.JsonUsernamePasswordAuthenticationFilter;
import cn.flying.filter.JwtAuthenticationFilter;
import cn.flying.filter.RequestLogFilter;
import cn.flying.service.AccountService;
import cn.flying.service.LoginSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessEventPublishingLogoutHandler;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * @program: RecordPlatform
 * @description: SpringSecurity配置类
 * @author flyingcoding
 * @create: 2025-01-16 11:23
 */
@Configuration
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Resource
    JwtAuthenticationFilter jwtAuthenticationFilter;

    @Resource
    RequestLogFilter requestLogFilter;

    @Resource
    JwtUtils utils;

    @Resource
    AccountService service;

    @Resource
    LoginSecurityService loginSecurityService;

    @Resource
    AuthenticationConfiguration authenticationConfiguration;

    @Resource
    ObjectMapper objectMapper;

    /**
     * 针对于 SpringSecurity 6 的新版配置方法
     * @param http 配置器
     * @return 自动构建的内置过滤器链
     * @throws Exception 可能的异常
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JsonUsernamePasswordAuthenticationFilter jsonLoginFilter = buildJsonLoginFilter();
        return http
                .authorizeHttpRequests(conf -> conf
                        // 公开端点
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/ask-code",
                                "/api/v1/auth/register",
                                "/api/v1/auth/reset-confirm",
                                "/api/v1/auth/reset-password",
                                "/api/v1/files/getSharingFiles",
                                "/api/v1/files/public/download",
                                "/api/v1/files/public/decryptInfo",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/v1/images/download/images/**").permitAll()
                        // SSE 短期令牌发行需要用户已认证
                        .requestMatchers("/api/v1/auth/sse-token").hasAnyRole(
                                UserRole.ROLE_DEFAULT.getRole(),
                                UserRole.ROLE_ADMINISTER.getRole(),
                                UserRole.ROLE_MONITOR.getRole())
                        // SSE 连接端点使用短期令牌认证，不使用常规 JWT
                        .requestMatchers("/api/v1/sse/connect").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/doc.html/**","/webjars/**","/favicon.ico").permitAll()
                        // 健康检查端点公开
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // 监控端点需要管理员或监控员角色
                        .requestMatchers("/actuator/**").hasAnyRole(
                                UserRole.ROLE_ADMINISTER.getRole(),
                                UserRole.ROLE_MONITOR.getRole())
                        // 系统日志和审计端点需要管理员或监控员角色
                        .requestMatchers("/api/v1/system/logs/**", "/api/v1/audit/**").hasAnyRole(
                                UserRole.ROLE_ADMINISTER.getRole(),
                                UserRole.ROLE_MONITOR.getRole())
                        // 其他所有请求需要任意角色
                        .anyRequest().hasAnyRole(
                                UserRole.ROLE_DEFAULT.getRole(),
                                UserRole.ROLE_ADMINISTER.getRole(),
                                UserRole.ROLE_MONITOR.getRole())
                )
                .logout(conf -> conf
                        .logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler(logoutSuccessEventPublishingLogoutHandler())
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .accessDeniedHandler(this::handleProcess)
                        .authenticationEntryPoint(this::handleProcess)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(requestLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, RequestLogFilter.class)
                .addFilterAt(jsonLoginFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 注册登出事件发布器，确保 LogoutSuccessEvent 可以被监听。
     * @return 登出事件发布处理器
     */
    @Bean
    public LogoutSuccessEventPublishingLogoutHandler logoutSuccessEventPublishingLogoutHandler() {
        return new LogoutSuccessEventPublishingLogoutHandler();
    }

    /**
     * 将多种类型的Handler整合到同一个方法中，包含：
     * - 登录成功
     * - 登录失败
     * - 未登录拦截/无权限拦截
     * @param request 请求
     * @param response 响应
     * @param exceptionOrAuthentication 异常或是验证实体
     * @throws IOException 可能的异常
     */
    private void handleProcess(HttpServletRequest request,
                               HttpServletResponse response,
                               Object exceptionOrAuthentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();

        // 获取用户名（用于登录安全检查）
        String username = request.getParameter("username");
        if ((username == null || username.isEmpty()) && request.getAttribute(Const.ATTR_LOGIN_USERNAME) != null) {
            username = String.valueOf(request.getAttribute(Const.ATTR_LOGIN_USERNAME));
        }

        if(exceptionOrAuthentication instanceof AccessDeniedException exception) {
            writer.write(Result
                    .error(exception.getMessage()).toJson());
        } else if(exceptionOrAuthentication instanceof AuthenticationException) {
            // 登录失败处理
            handleLoginFailure(username, writer);
        } else if(exceptionOrAuthentication instanceof Exception exception) {
            writer.write(Result
                    .error(exception.getMessage()).toJson());
        } else if(exceptionOrAuthentication instanceof Authentication authentication){
            // 登录成功处理
            handleLoginSuccess(username, authentication, writer);
        }
    }

    /**
     * 处理登录失败
     */
    private void handleLoginFailure(String username, PrintWriter writer) {
        if (username != null && !username.isEmpty()) {
            // 检查是否已被锁定
            if (loginSecurityService.isAccountLocked(username)) {
                long remainingTime = loginSecurityService.getRemainingLockTime(username);
                log.warn("账户 [{}] 已被锁定，剩余锁定时间: {}秒", username, remainingTime);
                writer.write(Result.error(ResultEnum.USER_ACCOUNT_LOCKED).toJson());
                return;
            }

            // 记录登录失败
            int failCount = loginSecurityService.recordLoginFailure(username);
            int remaining = loginSecurityService.getRemainingAttempts(username);
            log.warn("账户 [{}] 登录失败，当前失败次数: {}, 剩余尝试次数: {}", username, failCount, remaining);

            if (remaining <= 0) {
                writer.write(Result.error(ResultEnum.USER_ACCOUNT_LOCKED).toJson());
                return;
            }
        }

        writer.write(Result.error(ResultEnum.USER_LOGIN_ERROR).toJson());
    }

    /**
     * 处理登录成功
     * 注意：租户ID由请求头 X-Tenant-ID 提供，TenantFilter 已设置到 TenantContext
     */
    private void handleLoginSuccess(String username, Authentication authentication, PrintWriter writer) throws IOException {
        User user = (User) authentication.getPrincipal();
        Account account = service.findAccountByNameOrEmail(user.getUsername());

        // 清除登录失败记录
        if (username != null && !username.isEmpty()) {
            loginSecurityService.clearLoginFailure(username);
        }

        // 确保使用Long类型的用户ID
        Long userId = account.getId();
        String jwt = utils.createJwt(user, account.getUsername(), userId, account.getTenantId());

        if(jwt == null) {
            writer.write(Result.error(ResultEnum.PERMISSION_LIMIT).toJson());
        } else {
            AuthorizeVO vo = account.asViewObject(AuthorizeVO.class, o -> o.setToken(jwt));
            vo.setExpire(utils.expireTime());
            writer.write(Result.success(vo).toJson());
        }
    }

    /**
     *  flyingcoding
     *
     * @return configured JSON login filter
     * @throws Exception when authentication manager cannot be resolved
     */
    private JsonUsernamePasswordAuthenticationFilter buildJsonLoginFilter() throws Exception {
        JsonUsernamePasswordAuthenticationFilter filter =
                new JsonUsernamePasswordAuthenticationFilter(objectMapper);
        AuthenticationManager authenticationManager = authenticationConfiguration.getAuthenticationManager();
        filter.setAuthenticationManager(authenticationManager);
        filter.setFilterProcessesUrl("/api/v1/auth/login");
        filter.setAuthenticationSuccessHandler(this::handleProcess);
        filter.setAuthenticationFailureHandler(this::handleProcess);
        return filter;
    }

    /**
     * 退出登录处理，将对应的Jwt令牌列入黑名单不再使用
     * @param request 请求
     * @param response 响应
     * @param authentication 验证实体
     * @throws IOException 可能的异常
     */
    private void onLogoutSuccess(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        String authorization = request.getHeader("Authorization");
        if(utils.invalidateJwt(authorization)) {
            writer.write(Result.success("退出登录成功").toJson());
            return;
        }
        writer.write(Result.error(ResultEnum.PERMISSION_TOKEN_EXPIRED).toJson());
    }
}
