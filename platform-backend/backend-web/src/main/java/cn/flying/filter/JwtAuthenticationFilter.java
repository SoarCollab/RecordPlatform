package cn.flying.filter;

import cn.flying.common.util.Const;
import cn.flying.common.util.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 用于对请求头中Jwt令牌进行校验的工具，为当前请求添加用户验证信息
 * 并将用户的ID存放在请求对象属性中，方便后续使用
 * 同时将用户ID放入MDC上下文，便于日志记录
 */
@Component
@Order(Const.SECURITY_ORDER)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Resource
    JwtUtils utils;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        DecodedJWT jwt = utils.resolveJwt(authorization);
        if(jwt != null) {
            UserDetails user = utils.toUser(jwt);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            
            // 从JWT中获取用户ID
            Long userId = utils.toId(jwt);
            
            // 将用户ID存入请求属性
            request.setAttribute(Const.ATTR_USER_ID, userId);
            
            // 将用户ID放入MDC上下文，便于日志记录
            if (userId != null) {
                MDC.put(Const.ATTR_USER_ID, userId.toString());
            }
        }
        
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清理MDC上下文
            MDC.remove("userId");
        }
    }
}
