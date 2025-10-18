package cn.flying.monitor.server.filter;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.Const;
import cn.flying.monitor.server.utils.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 用于对请求头中Jwt令牌进行校验的工具，为当前请求添加用户验证信息
 * 并将用户的ID存放在请求对象属性中，方便后续使用
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Resource
    JwtUtils utils;
    @Resource
    ClientService clientService;
    @Resource
    AccountService accountService;
    @Resource
    ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String uri = request.getRequestURI();
        if (uri.startsWith("/monitor")) {
            if (!uri.endsWith("/register")) {
                Client client = clientService.findClientByToken(authorization);
                if (client == null) {
                    writeJson(response, HttpStatus.UNAUTHORIZED, Result.error("未注册"));
                    return;
                } else {
                    request.setAttribute(Const.ATTR_CLIENT, client);
                }
            }
        } else {
            DecodedJWT jwt = utils.resolveJwt(authorization);
            if (jwt != null) {
                UserDetails user = utils.toUser(jwt);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(Const.ATTR_USER_ID, utils.toId(jwt));
                request.setAttribute(Const.ATTR_USER_ROLE, new ArrayList<>(user.getAuthorities()).getFirst().getAuthority());

                if (request.getRequestURI().startsWith("/terminal/") && !accessShell(
                        (int) request.getAttribute(Const.ATTR_USER_ID),
                        (String) request.getAttribute(Const.ATTR_USER_ROLE),
                        Integer.parseInt(request.getRequestURI().substring(10)))) {
                    writeJson(response, HttpStatus.UNAUTHORIZED, Result.error("无权访问"));
                    return;
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean accessShell(int userId, String userRole, int clientId) {
        if (Const.ROLE_ADMIN.equals(userRole.substring(5))) {
            return true;
        } else {
            Account account = accountService.getById(userId);
            return account.getClientList().contains(clientId);
        }
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Result<?> body) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
