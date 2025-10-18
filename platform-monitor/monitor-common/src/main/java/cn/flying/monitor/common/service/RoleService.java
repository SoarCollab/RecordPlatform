package cn.flying.monitor.common.service;

import cn.flying.monitor.common.entity.Role;

import java.util.List;
import java.util.Optional;

/**
 * Role management service interface
 */
public interface RoleService {
    
    /**
     * Find role by name
     */
    Optional<Role> findByName(String name);
    
    /**
     * Find role by ID
     */
    Optional<Role> findById(Long id);
    
    /**
     * Create new role
     */
    Role createRole(String name, String description, List<String> permissions);
    
    /**
     * Update role permissions
     */
    void updatePermissions(Long roleId, List<String> permissions);
    
    /**
     * Delete role
     */
    void deleteRole(Long roleId);
    
    /**
     * Get all roles
     */
    List<Role> getAllRoles();
    
    /**
     * Initialize default roles
     */
    void initializeDefaultRoles();
    
    /**
     * Check if role exists
     */
    boolean existsByName(String name);
}