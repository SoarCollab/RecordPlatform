package cn.flying.monitor.server.filter;

import cn.flying.monitor.server.entity.RestBean;
import cn.flying.monitor.server.entity.dto.Account;
import cn.flying.monitor.server.entity.dto.Client;
import cn.flying.monitor.server.service.AccountService;
import cn.flying.monitor.server.service.ClientService;
import cn.flying.monitor.server.utils.Const;
import cn.flying.monitor.server.utils.JwtUtils;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
                    response.setStatus(401);
                    response.setCharacterEncoding("utf-8");
                    response.getWriter().write(RestBean.failure(401, "未注册").asJsonString());
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
            }
            // WebSocket 终端访问控制：无论是否使用JWT，都需要校验访问权限
            if (request.getRequestURI().startsWith("/terminal/")) {
                Integer uid = (Integer) request.getAttribute(Const.ATTR_USER_ID);
                String role = (String) request.getAttribute(Const.ATTR_USER_ROLE);

                // 如果未通过JWT设置，则尝试从会话认证中解析
                if (uid == null || role == null) {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User u) {
                        Account acc = accountService.findAccountByNameOrEmail(u.getUsername());
                        if (acc != null) {
                            uid = acc.getId();
                            role = new ArrayList<>(u.getAuthorities()).getFirst().getAuthority();
                            request.setAttribute(Const.ATTR_USER_ID, uid);
                            request.setAttribute(Const.ATTR_USER_ROLE, role);
                        }
                    }
                }

                int clientId = 0;
                try {
                    clientId = Integer.parseInt(request.getRequestURI().substring(10));
                } catch (Exception ignored) {}

                if (uid == null || role == null || !accessShell(uid, role, clientId)) {
                    response.setStatus(401);
                    response.setCharacterEncoding("utf-8");
                    response.getWriter().write(RestBean.failure(401, "无权访问").asJsonString());
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
}
