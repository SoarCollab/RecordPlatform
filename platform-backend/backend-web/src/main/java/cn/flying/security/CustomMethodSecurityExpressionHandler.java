package cn.flying.security;

import cn.flying.service.PermissionService;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 自定义方法安全表达式处理器
 * 扩展 @PreAuthorize 支持自定义权限检查方法
 */
public class CustomMethodSecurityExpressionHandler extends DefaultMethodSecurityExpressionHandler {

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private final PermissionService permissionService;

    public CustomMethodSecurityExpressionHandler(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 构建基于认证信息提供者与方法调用的 SpEL 评估上下文。
     *
     * @param authentication 认证信息提供者
     * @param invocation 方法调用
     * @return 评估上下文
     */
    @Override
    public EvaluationContext createEvaluationContext(Supplier<Authentication> authentication,
                                                      MethodInvocation invocation) {
        CustomMethodSecurityExpressionRoot root = buildExpressionRoot(authentication, invocation);
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                root,
                getSpecificMethod(invocation),
                invocation.getArguments(),
                getParameterNameDiscoverer());
        context.setBeanResolver(getBeanResolver());
        return context;
    }

    /**
     * 构建自定义表达式根并填充方法安全所需上下文。
     *
     * @param authentication 认证信息提供者
     * @param invocation 方法调用
     * @return 自定义表达式根
     */
    private CustomMethodSecurityExpressionRoot buildExpressionRoot(Supplier<Authentication> authentication,
                                                                   MethodInvocation invocation) {
        CustomMethodSecurityExpressionRoot root =
                new CustomMethodSecurityExpressionRoot(authentication, permissionService);
        root.setThis(invocation.getThis());
        root.setPermissionEvaluator(getPermissionEvaluator());
        root.setTrustResolver(this.trustResolver);
        root.setRoleHierarchy(getRoleHierarchy());
        root.setDefaultRolePrefix(getDefaultRolePrefix());
        return root;
    }

    /**
     * 获取当前调用的最具体方法（解析代理类上的实际方法）。
     *
     * @param invocation 方法调用
     * @return 最具体的方法
     */
    private Method getSpecificMethod(MethodInvocation invocation) {
        Method method = invocation.getMethod();
        Object target = invocation.getThis();
        if (target == null) {
            return method;
        }
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
        if (targetClass == null) {
            return method;
        }
        return AopUtils.getMostSpecificMethod(method, targetClass);
    }

    /**
     * 构建自定义表达式根（兼容旧的认证对象入口）。
     *
     * @param authentication 认证信息
     * @param invocation 方法调用
     * @return 表达式根
     */
    @Override
    protected MethodSecurityExpressionOperations createSecurityExpressionRoot(
            Authentication authentication, MethodInvocation invocation) {
        return buildExpressionRoot(() -> authentication, invocation);
    }
}
