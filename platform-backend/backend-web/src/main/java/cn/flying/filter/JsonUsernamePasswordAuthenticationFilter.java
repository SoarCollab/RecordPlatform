package cn.flying.filter;

import cn.flying.common.util.Const;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Authentication filter that supports JSON body login.
 */
public class JsonUsernamePasswordAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

    private final ObjectMapper objectMapper;

    /**
     * Create a JSON login authentication filter with an ObjectMapper.
     *
     * @param objectMapper JSON parser
     */
    public JsonUsernamePasswordAuthenticationFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Read username/password from JSON body and perform authentication.
     *
     * @param request  HTTP request
     * @param response HTTP response
     * @return authentication result
     * @throws AuthenticationException authentication error
     */
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        if (!isJsonRequest(request)) {
            return super.attemptAuthentication(request, response);
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            throw new AuthenticationServiceException("Authentication method not supported: " + request.getMethod());
        }

        LoginRequest loginRequest = readLoginRequest(request);
        String username = loginRequest.getUsername();
        String password = loginRequest.getPassword();

        if (!StringUtils.hasText(username)) {
            username = "";
        } else {
            username = username.trim();
        }
        if (password == null) {
            password = "";
        }

        // Store username for failure handling statistics
        request.setAttribute(Const.ATTR_LOGIN_USERNAME, username);

        UsernamePasswordAuthenticationToken authRequest =
                new UsernamePasswordAuthenticationToken(username, password);
        setDetails(request, authRequest);
        return this.getAuthenticationManager().authenticate(authRequest);
    }

    /**
     * Check if the request is a JSON login request.
     *
     * @param request HTTP request
     * @return true when JSON request
     */
    private boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        return contentType.toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Read and parse login info from JSON body.
     *
     * @param request HTTP request
     * @return login request payload
     * @throws AuthenticationServiceException when parsing fails
     */
    private LoginRequest readLoginRequest(HttpServletRequest request) {
        try {
            return objectMapper.readValue(request.getInputStream(), LoginRequest.class);
        } catch (IOException e) {
            throw new AuthenticationServiceException("Invalid login request body", e);
        }
    }

    /**
     * JSON login request payload.
     */
    @Setter
    @Getter
    public static class LoginRequest {

        private String username;

        private String password;

    }
}
