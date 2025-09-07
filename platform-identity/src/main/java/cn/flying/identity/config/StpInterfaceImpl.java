package cn.flying.identity.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义权限加载接口实现类
 * 根据用户角色返回相应的权限和角色列表
 * 实现 Sa-Token 的权限验证扩展功能
 */
@Component    // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展
public class StpInterfaceImpl implements StpInterface {

    /**
     * 账户数据访问层
     * 用于查询用户信息和角色权限
     */
    @Resource
    private AccountMapper accountMapper;

    /**
     * 返回一个账号所拥有的权限码集合
     *
     * @param loginId   用户ID
     * @param loginType 登录类型
     * @return 权限码列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 根据用户ID查询用户信息
        Account account = accountMapper.selectById(Long.valueOf(loginId.toString()));
        if (account == null) {
            return new ArrayList<>();
        }

        List<String> permissions = new ArrayList<>();
        String role = account.getRole();

        // 根据角色分配权限，保持与原backend模块一致
        switch (role) {
            case "admin":
                // 管理员拥有所有权限
                permissions.addAll(List.of(
                        // 用户管理权限
                        "user:add", "user:update", "user:delete", "user:view", "user:list",
                        "user:reset-password", "user:disable", "user:enable",
                        // 系统管理权限  
                        "system:config", "system:monitor", "system:backup", "system:maintenance",
                        // 审计日志权限
                        "audit:view", "audit:export", "audit:delete", "audit:config",
                        // Token监控权限
                        "token:monitor:record", "token:monitor:query", "token:monitor:stats",
                        "token:monitor:risk", "token:monitor:detect", "token:monitor:handle",
                        "token:monitor:report", "token:monitor:alert", "token:monitor:clean",
                        "token:monitor:export",
                        // OAuth管理权限
                        "oauth:client:register", "oauth:client:update", "oauth:client:delete",
                        "oauth:client:view", "oauth:token:revoke",
                        // 文件管理权限
                        "file:upload", "file:download", "file:delete", "file:manage",
                        // 统计分析权限
                        "stats:user", "stats:operation", "stats:system"
                ));
                break;
            case "monitor":
                // 监控员权限
                permissions.addAll(List.of(
                        "user:view", "user:list",
                        "system:monitor", "audit:view", "audit:export",
                        "token:monitor:query", "token:monitor:stats", "token:monitor:report",
                        "stats:user", "stats:operation", "stats:system"
                ));
                break;
            case "user":
                // 普通用户权限
                permissions.addAll(List.of(
                        "user:view:self", "user:update:self", "user:change-password",
                        "file:upload", "file:download", "file:view:self",
                        "oauth:authorize", "sso:login"
                ));
                break;
            default:
                // 默认最小权限
                permissions.add("user:view:self");
                break;
        }

        return permissions;
    }

    /**
     * 返回一个账号所拥有的角色标识集合
     *
     * @param loginId   用户ID
     * @param loginType 登录类型
     * @return 角色标识列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 根据用户ID查询用户信息
        Account account = accountMapper.selectById(Long.valueOf(loginId.toString()));
        if (account == null) {
            return new ArrayList<>();
        }

        // 返回用户角色
        return List.of(account.getRole());
    }

}