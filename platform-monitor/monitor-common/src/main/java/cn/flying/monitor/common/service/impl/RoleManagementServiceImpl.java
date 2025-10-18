package cn.flying.monitor.common.service.impl;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.mapper.RoleMapper;
import cn.flying.monitor.common.security.Permission;
import cn.flying.monitor.common.security.RbacService;
import cn.flying.monitor.common.service.RoleManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Role management service implementation
 */
@Service
public class RoleManagementServiceImpl implements RoleManagementService {

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RbacService rbacService;

    @Override
    @Transactional
    public Role createRole(String name, String code, String description, Set<String> permissions) {
        Role role = Role.builder()
                .name(name)
                .code(code)
                .description(description)
                .status(Role.RoleStatus.ACTIVE)
                .isSystem(false)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deleted(false)
                .build();

        roleMapper.insert(role);

        // Set permissions in RBAC service
        if (permissions != null && !permissions.isEmpty()) {
            rbacService.setRolePermissions(code, permissions);
        }

        System.out.println("Created role: " + name + " with code: " + code);
        return role;
    }

    @Override
    @Transactional
    public Role updateRole(Long roleId, String name, String description, Set<String> permissions) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        role.setName(name);
        role.setDescription(description);
        role.setUpdatedAt(Instant.now());

        roleMapper.updateById(role);

        // Update permissions in RBAC service
        if (permissions != null) {
            rbacService.setRolePermissions(role.getCode(), permissions);
        }

        System.out.println("Updated role: " + name);
        return role;
    }

    @Override
    @Transactional
    public void deleteRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            return;
        }

        if (role.getIsSystem() != null && role.getIsSystem()) {
            throw new IllegalArgumentException("Cannot delete system role: " + role.getName());
        }

        // Soft delete
        role.setDeleted(true);
        role.setUpdatedAt(Instant.now());
        roleMapper.updateById(role);

        // Clear permissions from RBAC service
        rbacService.setRolePermissions(role.getCode(), new HashSet<>());

        System.out.println("Deleted role: " + role.getName());
    }

    @Override
    public Role getRoleById(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role != null) {
            // Load permissions
            Set<String> permissions = rbacService.getRolePermissions(role.getCode());
            role.setPermissions(permissions.stream().toList());
        }
        return role;
    }

    @Override
    public Role getRoleByCode(String code) {
        Role role = roleMapper.findByCode(code);
        if (role != null) {
            // Load permissions
            Set<String> permissions = rbacService.getRolePermissions(code);
            role.setPermissions(permissions.stream().toList());
        }
        return role;
    }

    @Override
    public List<Role> getAllRoles() {
        List<Role> roles = roleMapper.selectList(null);
        // Load permissions for each role
        for (Role role : roles) {
            Set<String> permissions = rbacService.getRolePermissions(role.getCode());
            role.setPermissions(permissions.stream().toList());
        }
        return roles;
    }

    @Override
    public List<Role> getActiveRoles() {
        List<Role> roles = roleMapper.findActiveRoles();
        // Load permissions for each role
        for (Role role : roles) {
            Set<String> permissions = rbacService.getRolePermissions(role.getCode());
            role.setPermissions(permissions.stream().toList());
        }
        return roles;
    }

    @Override
    @Transactional
    public void addPermissionToRole(Long roleId, String permission) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        Set<String> permissions = rbacService.getRolePermissions(role.getCode());
        permissions.add(permission);
        rbacService.setRolePermissions(role.getCode(), permissions);

        System.out.println("Added permission " + permission + " to role " + role.getName());
    }

    @Override
    @Transactional
    public void removePermissionFromRole(Long roleId, String permission) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        Set<String> permissions = rbacService.getRolePermissions(role.getCode());
        permissions.remove(permission);
        rbacService.setRolePermissions(role.getCode(), permissions);

        System.out.println("Removed permission " + permission + " from role " + role.getName());
    }

    @Override
    @Transactional
    public void setRolePermissions(Long roleId, Set<String> permissions) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        rbacService.setRolePermissions(role.getCode(), permissions);
        System.out.println("Set permissions for role " + role.getName() + ": " + permissions);
    }

    @Override
    public Set<String> getRolePermissions(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            return new HashSet<>();
        }

        return rbacService.getRolePermissions(role.getCode());
    }

    @Override
    public boolean roleHasPermission(Long roleId, String permission) {
        Set<String> permissions = getRolePermissions(roleId);
        return permissions.contains(permission);
    }

    @Override
    public List<Permission> getAllAvailablePermissions() {
        return Arrays.asList(Permission.values());
    }

    @Override
    @Transactional
    public void activateRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        role.setStatus(Role.RoleStatus.ACTIVE);
        role.setUpdatedAt(Instant.now());
        roleMapper.updateById(role);

        System.out.println("Activated role: " + role.getName());
    }

    @Override
    @Transactional
    public void deactivateRole(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found: " + roleId);
        }

        if (role.getIsSystem() != null && role.getIsSystem()) {
            throw new IllegalArgumentException("Cannot deactivate system role: " + role.getName());
        }

        role.setStatus(Role.RoleStatus.INACTIVE);
        role.setUpdatedAt(Instant.now());
        roleMapper.updateById(role);

        System.out.println("Deactivated role: " + role.getName());
    }
}