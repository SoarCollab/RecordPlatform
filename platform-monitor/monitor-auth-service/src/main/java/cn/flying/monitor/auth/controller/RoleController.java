package cn.flying.monitor.auth.controller;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.security.Permission;
import cn.flying.monitor.common.security.RequirePermission;
import cn.flying.monitor.common.service.RoleManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * Role management controller with permission validation
 */
@RestController
@RequestMapping("/api/roles")
@RequirePermission("system:admin") // All endpoints require system admin
public class RoleController {

    @Autowired
    private RoleManagementService roleManagementService;

    /**
     * Get all roles
     */
    @GetMapping
    @RequirePermission(anyOf = {"system:admin", "user:read"})
    public ResponseEntity<List<Role>> getAllRoles() {
        List<Role> roles = roleManagementService.getAllRoles();
        return ResponseEntity.ok(roles);
    }

    /**
     * Get active roles only
     */
    @GetMapping("/active")
    @RequirePermission(anyOf = {"system:admin", "user:read"})
    public ResponseEntity<List<Role>> getActiveRoles() {
        List<Role> roles = roleManagementService.getActiveRoles();
        return ResponseEntity.ok(roles);
    }

    /**
     * Get role by ID
     */
    @GetMapping("/{roleId}")
    @RequirePermission(anyOf = {"system:admin", "user:read"})
    public ResponseEntity<Role> getRoleById(@PathVariable Long roleId) {
        Role role = roleManagementService.getRoleById(roleId);
        if (role == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(role);
    }

    /**
     * Create new role
     */
    @PostMapping
    @RequirePermission("system:admin")
    public ResponseEntity<Role> createRole(
            @RequestParam String name,
            @RequestParam String code,
            @RequestParam String description,
            @RequestBody(required = false) Set<String> permissions) {
        
        Role role = roleManagementService.createRole(name, code, description, permissions);
        return ResponseEntity.ok(role);
    }

    /**
     * Update role
     */
    @PutMapping("/{roleId}")
    @RequirePermission("system:admin")
    public ResponseEntity<Role> updateRole(
            @PathVariable Long roleId,
            @RequestParam String name,
            @RequestParam String description,
            @RequestBody(required = false) Set<String> permissions) {
        
        Role role = roleManagementService.updateRole(roleId, name, description, permissions);
        return ResponseEntity.ok(role);
    }

    /**
     * Delete role
     */
    @DeleteMapping("/{roleId}")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId) {
        roleManagementService.deleteRole(roleId);
        return ResponseEntity.ok().build();
    }

    /**
     * Add permission to role
     */
    @PostMapping("/{roleId}/permissions/{permission}")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> addPermissionToRole(
            @PathVariable Long roleId,
            @PathVariable String permission) {
        
        roleManagementService.addPermissionToRole(roleId, permission);
        return ResponseEntity.ok().build();
    }

    /**
     * Remove permission from role
     */
    @DeleteMapping("/{roleId}/permissions/{permission}")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> removePermissionFromRole(
            @PathVariable Long roleId,
            @PathVariable String permission) {
        
        roleManagementService.removePermissionFromRole(roleId, permission);
        return ResponseEntity.ok().build();
    }

    /**
     * Set role permissions
     */
    @PutMapping("/{roleId}/permissions")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> setRolePermissions(
            @PathVariable Long roleId,
            @RequestBody Set<String> permissions) {
        
        roleManagementService.setRolePermissions(roleId, permissions);
        return ResponseEntity.ok().build();
    }

    /**
     * Get role permissions
     */
    @GetMapping("/{roleId}/permissions")
    @RequirePermission(anyOf = {"system:admin", "user:read"})
    public ResponseEntity<Set<String>> getRolePermissions(@PathVariable Long roleId) {
        Set<String> permissions = roleManagementService.getRolePermissions(roleId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Get all available permissions
     */
    @GetMapping("/permissions/available")
    @RequirePermission(anyOf = {"system:admin", "user:read"})
    public ResponseEntity<List<Permission>> getAllAvailablePermissions() {
        List<Permission> permissions = roleManagementService.getAllAvailablePermissions();
        return ResponseEntity.ok(permissions);
    }

    /**
     * Activate role
     */
    @PostMapping("/{roleId}/activate")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> activateRole(@PathVariable Long roleId) {
        roleManagementService.activateRole(roleId);
        return ResponseEntity.ok().build();
    }

    /**
     * Deactivate role
     */
    @PostMapping("/{roleId}/deactivate")
    @RequirePermission("system:admin")
    public ResponseEntity<Void> deactivateRole(@PathVariable Long roleId) {
        roleManagementService.deactivateRole(roleId);
        return ResponseEntity.ok().build();
    }
}