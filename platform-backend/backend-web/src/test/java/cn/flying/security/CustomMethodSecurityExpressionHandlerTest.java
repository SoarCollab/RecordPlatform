package cn.flying.security;

import cn.flying.service.PermissionService;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomMethodSecurityExpressionHandler Tests")
class CustomMethodSecurityExpressionHandlerTest {

    @Mock
    private PermissionService permissionService;

    private CustomMethodSecurityExpressionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CustomMethodSecurityExpressionHandler(permissionService);
    }

    private MethodInvocation stubInvocation(Object target) throws Exception {
        MethodInvocation inv = mock(MethodInvocation.class);
        Method method = SampleController.class.getMethod("sampleMethod");
        when(inv.getMethod()).thenReturn(method);
        when(inv.getArguments()).thenReturn(new Object[0]);
        when(inv.getThis()).thenReturn(target);
        return inv;
    }

    @Nested
    @DisplayName("createEvaluationContext")
    class CreateEvaluationContextTests {

        @Test
        @DisplayName("should return evaluation context with expression root")
        void shouldReturnEvaluationContextWithRoot() throws Exception {
            MethodInvocation inv = stubInvocation(new SampleController());
            Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass");

            EvaluationContext ctx = handler.createEvaluationContext(() -> auth, inv);

            assertThat(ctx).isNotNull();
            Object root = ctx.getRootObject().getValue();
            assertThat(root).isInstanceOf(CustomMethodSecurityExpressionRoot.class);
        }

        @Test
        @DisplayName("should handle null target gracefully")
        void shouldHandleNullTarget() throws Exception {
            MethodInvocation inv = stubInvocation(null);
            Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass");

            EvaluationContext ctx = handler.createEvaluationContext(() -> auth, inv);

            assertThat(ctx).isNotNull();
        }
    }

    @Nested
    @DisplayName("createSecurityExpressionRoot")
    class CreateSecurityExpressionRootTests {

        @Test
        @DisplayName("should return CustomMethodSecurityExpressionRoot")
        void shouldReturnCustomRoot() throws Exception {
            MethodInvocation inv = mock(MethodInvocation.class);
            when(inv.getThis()).thenReturn(new SampleController());
            Authentication auth = new UsernamePasswordAuthenticationToken("user", "pass");

            MethodSecurityExpressionOperations root = handler.createSecurityExpressionRoot(auth, inv);

            assertThat(root).isInstanceOf(CustomMethodSecurityExpressionRoot.class);
        }
    }

    /** Minimal controller class used as a proxy-resolution target. */
    public static class SampleController {
        public void sampleMethod() {
            // no-op
        }
    }
}
