package cn.flying.monitor.common.service.impl;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.mapper.RoleMapper;
import cn.flying.monitor.common.security.Permission;
import cn.flying.monitor.common.security.RbacService;
import cn.flying.monitor.common.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Role service implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {
    
    private final RoleMapper roleMapper;
    private final RbacService rbacService;
    
    @Override
    public Optional<Role> findByName(String name) {
        return Optional.ofNullable(roleMapper.findByName(name));
    }
    
    @Override
    public Optional<Role> findById(Long id) {
        return Optional.ofNullable(roleMapper.selectById(id));
    }
    
    @Override
    @Transactional
    public Role createRole(String name, String description, List<String> permissions) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setPermissions(permissions);
        
        roleMapper.insert(role);
        log.info("Created role: {} with permissions: {}", name, permissions);
        return role;
    }
    
    @Override
    @Transactional
    public void updatePermissions(Long roleId, List<String> permissions) {
        Role role = roleMapper.selectById(roleId);
        if (role != null) {
            role.setPermissions(permissions);
            roleMapper.updateById(role);
            log.info("Updated permissions for role {}: {}", role.getName(), permissions);
        }
    }
    
    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role != null) {
            roleMapper.deleteById(roleId);
            log.info("Deleted role: {}", role.getName());
        }
    }
    
    @Override
    public List<Role> getAllRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<>());
    }
    
    @Override
    @Transactional
    public void initializeDefaultRoles() {
        // Admin role
        if (!existsByName("admin")) {
            createRole("admin", "System Administrator", List.of("*"));
        }
        
        // Operator role
        if (!existsByName("operator")) {
            createRole("operator", "System Operator", List.of(
                Permission.CLIENT_READ.getCode(), Permission.CLIENT_UPDATE.getCode(),
                Permission.MONITOR_DATA_READ.getCode(), Permission.MONITOR_DATA_WRITE.getCode(),
                Permission.ALERT_READ.getCode(), Permission.ALERT_UPDATE.getCode(),
                Permission.WEBSOCKET_TERMINAL.getCode()
            ));
        }
        
        // Viewer role
        if (!existsByName("viewer")) {
            createRole("viewer", "Read-only Viewer", List.of(
                Permission.CLIENT_READ.getCode(),
                Permission.MONITOR_DATA_READ.getCode(),
                Permission.ALERT_READ.getCode()
            ));
        }
        
        // User role
        if (!existsByName("user")) {
            createRole("user", "Regular User", List.of(
                Permission.CLIENT_READ.getCode(),
                Permission.MONITOR_DATA_READ.getCode()
            ));
        }
        
        log.info("Default roles initialized");
    }
    
    @Override
    public boolean existsByName(String name) {
        return roleMapper.existsByName(name);
    }
}