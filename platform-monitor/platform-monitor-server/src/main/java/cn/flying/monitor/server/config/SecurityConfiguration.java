package cn.flying.monitor.server.config;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.vo.response.AuthorizeVO;
import cn.flying.monitor.server.filter.JwtFilter;
import cn.flying.monitor.server.filter.RequestLogFilter;
import cn.flying.monitor.server.handler.OAuth2LoginSuccessHandler;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.utils.Const;
import cn.flying.monitor.server.utils.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.nio.charset.StandardCharsets;

/**
 * SpringSecurity相关配置
 * 支持本地登录和OAuth2单点登录
 */
@Configuration
public class SecurityConfiguration {

    @Resource
    JwtFilter jwtFilter;

    @Resource
    RequestLogFilter requestLogFilter;

    @Resource
    JwtUtils utils;

    @Resource
    AccountService service;

    @Resource
    OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Resource
    ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(conf -> conf
                        .requestMatchers("terminal/**").permitAll()
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .requestMatchers("/monitor/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/doc.html/**", "/webjars/**", "/favicon.ico").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**", "/login").permitAll()
                        .requestMatchers("/api/user/sub/**").hasRole(Const.ROLE_ADMIN)
                        .anyRequest().hasAnyRole(Const.ROLE_DEFAULT, Const.ROLE_ADMIN)
                )
                .formLogin(conf -> conf
                        .loginProcessingUrl("/api/auth/login")
                        .failureHandler(this::handleProcess)
                        .successHandler(this::handleProcess)
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(oAuth2LoginSuccessHandler)
                        .failureUrl("/login?error=oauth_failed")
                        .permitAll()
                )
                .logout(conf -> conf
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess)
                )
                .exceptionHandling(conf -> conf
                        .accessDeniedHandler(this::handleProcess)
                        .authenticationEntryPoint(this::handleProcess)
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(conf -> conf
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(requestLogFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, RequestLogFilter.class)
                .build();
    }

    private void handleProcess(HttpServletRequest request,
                               HttpServletResponse response,
                               Object exceptionOrAuthentication) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (exceptionOrAuthentication instanceof AccessDeniedException exception) {
            writeJson(response, HttpStatus.FORBIDDEN, Result.error(exception.getMessage()));
        } else if (exceptionOrAuthentication instanceof Exception exception) {
            writeJson(response, HttpStatus.UNAUTHORIZED, Result.error(exception.getMessage()));
        } else if (exceptionOrAuthentication instanceof Authentication authentication) {
            User user = (User) authentication.getPrincipal();
            Account account = service.findAccountByNameOrEmail(user.getUsername());
            String jwt = utils.createJwt(user, account.getUsername(), account.getId());
            if (jwt == null) {
                writeJson(response, HttpStatus.TOO_MANY_REQUESTS, Result.error("登录验证频繁，请稍后再试"));
            } else {
                AuthorizeVO vo = account.asViewObject(AuthorizeVO.class, o -> o.setToken(jwt));
                vo.setExpire(utils.expireTime());
                writeJson(response, HttpStatus.OK, Result.success(vo, "登录成功"));
            }
        }
    }

    private void onLogoutSuccess(HttpServletRequest request,
                                 HttpServletResponse response,
                                 Authentication authentication) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String authorization = request.getHeader("Authorization");
        if (utils.invalidateJwt(authorization)) {
            writeJson(response, HttpStatus.OK, Result.success((Void) null, "退出登录成功"));
            return;
        }
        writeJson(response, HttpStatus.BAD_REQUEST, Result.error("退出登录失败"));
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Result<?> body) throws IOException {
        response.setStatus(status.value());
        PrintWriter writer = response.getWriter();
        objectMapper.writeValue(writer, body);
    }
}
