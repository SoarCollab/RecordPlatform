package cn.flying.monitor.common.service;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.security.Permission;

import java.util.List;
import java.util.Set;

/**
 * Role management service interface
 */
public interface RoleManagementService {
    
    /**
     * Create new role
     */
    Role createRole(String name, String code, String description, Set<String> permissions);
    
    /**
     * Update role
     */
    Role updateRole(Long roleId, String name, String description, Set<String> permissions);
    
    /**
     * Delete role
     */
    void deleteRole(Long roleId);
    
    /**
     * Get role by ID
     */
    Role getRoleById(Long roleId);
    
    /**
     * Get role by code
     */
    Role getRoleByCode(String code);
    
    /**
     * Get all roles
     */
    List<Role> getAllRoles();
    
    /**
     * Get active roles
     */
    List<Role> getActiveRoles();
    
    /**
     * Add permission to role
     */
    void addPermissionToRole(Long roleId, String permission);
    
    /**
     * Remove permission from role
     */
    void removePermissionFromRole(Long roleId, String permission);
    
    /**
     * Set role permissions
     */
    void setRolePermissions(Long roleId, Set<String> permissions);
    
    /**
     * Get role permissions
     */
    Set<String> getRolePermissions(Long roleId);
    
    /**
     * Check if role has permission
     */
    boolean roleHasPermission(Long roleId, String permission);
    
    /**
     * Get all available permissions
     */
    List<Permission> getAllAvailablePermissions();
    
    /**
     * Activate role
     */
    void activateRole(Long roleId);
    
    /**
     * Deactivate role
     */
    void deactivateRole(Long roleId);
}