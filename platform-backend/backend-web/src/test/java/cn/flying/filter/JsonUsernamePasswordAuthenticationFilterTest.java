package cn.flying.filter;

import cn.flying.common.util.Const;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonUsernamePasswordAuthenticationFilter 登录请求解析测试。
 */
class JsonUsernamePasswordAuthenticationFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * JSON literal null must be treated as an authentication failure instead of causing NPE.
     */
    @Test
    void shouldRejectJsonNullBody() throws Exception {
        JsonUsernamePasswordAuthenticationFilter filter = newFilter(mock(AuthenticationManager.class));
        MockHttpServletRequest request = jsonLoginRequest("null");

        assertThrows(AuthenticationServiceException.class,
                () -> filter.attemptAuthentication(request, new MockHttpServletResponse()));
    }

    /**
     * Valid JSON login still authenticates with trimmed username and original password.
     */
    @Test
    void shouldAuthenticateValidJsonLogin() throws Exception {
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        JsonUsernamePasswordAuthenticationFilter filter = newFilter(authenticationManager);
        MockHttpServletRequest request = jsonLoginRequest("{\"username\":\" alice \",\"password\":\"secret\"}");

        Authentication authentication = filter.attemptAuthentication(request, new MockHttpServletResponse());

        assertEquals("alice", authentication.getPrincipal());
        assertEquals("secret", authentication.getCredentials());
        assertEquals("alice", request.getAttribute(Const.ATTR_LOGIN_USERNAME));
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    /**
     * 构造带指定认证管理器的 JSON 登录过滤器。
     */
    private JsonUsernamePasswordAuthenticationFilter newFilter(AuthenticationManager authenticationManager) {
        JsonUsernamePasswordAuthenticationFilter filter =
                new JsonUsernamePasswordAuthenticationFilter(objectMapper);
        filter.setAuthenticationManager(authenticationManager);
        return filter;
    }

    /**
     * 构造 JSON 登录请求。
     */
    private MockHttpServletRequest jsonLoginRequest(String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType("application/json");
        request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return request;
    }
}
