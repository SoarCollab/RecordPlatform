package cn.flying.security;

import cn.flying.common.util.SecurityUtils;
import cn.flying.service.PermissionService;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

/**
 * 自定义方法安全表达式根
 * 提供自定义 SpEL 方法用于 @PreAuthorize 注解
 *
 * 使用示例:
 * - @PreAuthorize("hasPerm('file:read')")
 * - @PreAuthorize("hasPerm('file:write') or isAdmin()")
 * - @PreAuthorize("hasAnyPerm('file:read', 'file:admin')")
 * - @PreAuthorize("isOwner(#file.uid) or hasPerm('file:admin')")
 */
public class CustomMethodSecurityExpressionRoot extends SecurityExpressionRoot
        implements MethodSecurityExpressionOperations {

    private final PermissionService permissionService;
    private Object filterObject;
    private Object returnObject;
    private Object target;

    /**
     * 构建方法安全表达式根（支持延迟获取认证信息）。
     *
     * @param authenticationSupplier 认证信息提供者
     * @param permissionService 权限服务
     */
    public CustomMethodSecurityExpressionRoot(Supplier<Authentication> authenticationSupplier,
                                               PermissionService permissionService) {
        super(authenticationSupplier);
        this.permissionService = permissionService;
    }

    /**
     * 构建方法安全表达式根（直接使用认证信息）。
     *
     * @param authentication 认证信息
     * @param permissionService 权限服务
     */
    public CustomMethodSecurityExpressionRoot(Authentication authentication,
                                               PermissionService permissionService) {
        super(authentication);
        this.permissionService = permissionService;
    }

    /**
     * 检查当前用户是否拥有指定权限码
     * 使用: @PreAuthorize("hasPerm('module:action')")
     */
    public boolean hasPerm(String permissionCode) {
        return permissionService.hasPermission(permissionCode);
    }

    /**
     * 检查当前用户是否拥有任意一个指定权限
     * 使用: @PreAuthorize("hasAnyPerm('file:read', 'file:admin')")
     */
    public boolean hasAnyPerm(String... permissionCodes) {
        for (String code : permissionCodes) {
            if (permissionService.hasPermission(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查当前用户是否拥有所有指定权限
     * 使用: @PreAuthorize("hasAllPerm('file:read', 'file:write')")
     */
    public boolean hasAllPerm(String... permissionCodes) {
        for (String code : permissionCodes) {
            if (!permissionService.hasPermission(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查当前用户是否为资源所有者
     * 使用: @PreAuthorize("isOwner(#entity.uid)")
     */
    public boolean isOwner(Long resourceOwnerId) {
        return SecurityUtils.canAccessResource(resourceOwnerId);
    }

    /**
     * 检查当前用户是否为管理员
     * 使用: @PreAuthorize("isAdmin()")
     */
    public boolean isAdmin() {
        return SecurityUtils.isAdmin();
    }

    /**
     * 检查当前用户是否为监控员
     * 使用: @PreAuthorize("isMonitor()")
     */
    public boolean isMonitor() {
        return SecurityUtils.isMonitor();
    }

    /**
     * 检查当前用户是否为管理员或监控员
     * 使用: @PreAuthorize("isAdminOrMonitor()")
     */
    public boolean isAdminOrMonitor() {
        return SecurityUtils.isAdminOrMonitor();
    }

    @Override
    public void setFilterObject(Object filterObject) {
        this.filterObject = filterObject;
    }

    @Override
    public Object getFilterObject() {
        return this.filterObject;
    }

    @Override
    public void setReturnObject(Object returnObject) {
        this.returnObject = returnObject;
    }

    @Override
    public Object getReturnObject() {
        return this.returnObject;
    }

    /**
     * 设置方法调用目标对象，供 SpEL 中 this 引用使用。
     *
     * @param target 目标对象
     */
    public void setThis(Object target) {
        this.target = target;
    }

    @Override
    public Object getThis() {
        return this.target;
    }
}
