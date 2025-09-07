package cn.flying.identity.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义权限加载接口实现类
 * 根据用户角色返回相应的权限和角色列表
 */
@Component    // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展
public class StpInterfaceImpl implements StpInterface {

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

        // 根据角色分配权限
        switch (role) {
            case "admin":
                permissions.addAll(List.of(
                        "user.add", "user.update", "user.get", "user.delete",
                        "system.config", "system.monitor", "audit.view"
                ));
                break;
            case "user":
                permissions.addAll(List.of(
                        "user.get", "user.update.self", "file.upload", "file.download"
                ));
                break;
            default:
                permissions.add("user.get");
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