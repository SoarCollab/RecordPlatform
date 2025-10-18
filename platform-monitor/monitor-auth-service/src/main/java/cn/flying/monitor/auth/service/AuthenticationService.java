package cn.flying.monitor.auth.service;

import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.security.JwtTokenProvider;
import cn.flying.monitor.common.security.MfaService;
import cn.flying.monitor.common.security.RbacService;
import cn.flying.monitor.common.service.UserService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enhanced authentication service with MFA support
 */
@Service
public class AuthenticationService {
    
    private final UserService userService;
    private final MfaService mfaService;
    private final RbacService rbacService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationService(UserService userService, MfaService mfaService, 
                               RbacService rbacService, JwtTokenProvider jwtTokenProvider,
                               PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.mfaService = mfaService;
        this.rbacService = rbacService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }
    
    /**
     * Authenticate user with username/password
     * Returns different token types based on MFA requirements
     */
    public Map<String, Object> authenticate(String usernameOrEmail, String password, String clientIp) {
        Optional<User> userOpt = userService.findByUsernameOrEmail(usernameOrEmail);
        
        if (userOpt.isEmpty()) {
            System.out.println("Authentication failed - user not found: " + usernameOrEmail);
            throw new BadCredentialsException("Invalid credentials");
        }
        
        User user = userOpt.get();
        
        // Check if account is locked
        if (user.isAccountLocked()) {
            System.out.println("Authentication failed - account locked: " + user.getUsername());
            throw new LockedException("Account is locked");
        }
        
        // Verify password
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            userService.incrementFailedLoginAttempts(user.getId());
            System.out.println("Authentication failed - invalid password for user: " + user.getUsername());
            throw new BadCredentialsException("Invalid credentials");
        }
        
        // Reset failed attempts on successful password verification
        userService.resetFailedLoginAttempts(user.getId());
        
        // Get user permissions
        user = userService.findByIdWithRoles(user.getId()).orElse(user);
        
        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("mfaEnabled", user.requiresMfa());
        
        if (user.requiresMfa()) {
            // Generate pre-auth token for MFA flow
            String preAuthToken = jwtTokenProvider.generatePreAuthToken(
                user.getId().toString(), user.getUsername());
            
            result.put("token", preAuthToken);
            result.put("tokenType", "pre_auth");
            result.put("mfaRequired", true);
            
            System.out.println("Pre-authentication successful for user: " + user.getUsername() + " (MFA required)");
        } else {
            // Generate full access token
            String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId().toString(), 
                user.getUsername(), 
                rbacService.getUserPermissions(user),
                false, // MFA not verified (not required)
                false  // MFA not required
            );
            
            String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId().toString());
            
            result.put("accessToken", accessToken);
            result.put("refreshToken", refreshToken);
            result.put("tokenType", "access");
            result.put("mfaRequired", false);
            
            // Update last login
            userService.updateLastLogin(user.getId(), clientIp);
            
            System.out.println("Authentication successful for user: " + user.getUsername());
        }
        
        return result;
    }
    
    /**
     * Complete MFA authentication
     */
    public Map<String, Object> completeMfaAuthentication(String preAuthToken, String mfaCode, boolean isBackupCode, String clientIp) {
        if (!jwtTokenProvider.validateToken(preAuthToken) || !jwtTokenProvider.isPreAuthToken(preAuthToken)) {
            throw new BadCredentialsException("Invalid pre-auth token");
        }
        
        String userId = jwtTokenProvider.getUserId(preAuthToken);
        String username = jwtTokenProvider.getUsername(preAuthToken);
        
        Optional<User> userOpt = userService.findByIdWithRoles(Long.parseLong(userId));
        if (userOpt.isEmpty()) {
            throw new BadCredentialsException("User not found");
        }
        
        User user = userOpt.get();
        
        // Verify MFA code
        boolean mfaVerified = mfaService.verifyMfaCode(user.getId(), mfaCode, isBackupCode);
        if (!mfaVerified) {
            System.out.println("MFA verification failed for user: " + username);
            throw new BadCredentialsException("Invalid MFA code");
        }
        
        // Generate full access token
        String accessToken = jwtTokenProvider.generateAccessToken(
            userId, 
            username, 
            rbacService.getUserPermissions(user),
            true, // MFA verified
            true  // MFA required
        );
        
        String refreshToken = jwtTokenProvider.generateRefreshToken(userId);
        
        // Update last login
        userService.updateLastLogin(user.getId(), clientIp);
        
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("tokenType", "access");
        result.put("mfaVerified", true);
        
        System.out.println("MFA authentication completed for user: " + username);
        return result;
    }
    
    /**
     * Refresh access token
     */
    public Map<String, Object> refreshToken(String refreshToken) {
        if (!jwtTokenProvider.validateToken(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new BadCredentialsException("Invalid refresh token");
        }
        
        String userId = jwtTokenProvider.getUserId(refreshToken);
        Optional<User> userOpt = userService.findByIdWithRoles(Long.parseLong(userId));
        
        if (userOpt.isEmpty()) {
            throw new BadCredentialsException("User not found");
        }
        
        User user = userOpt.get();
        
        if (user.isAccountLocked()) {
            throw new LockedException("Account is locked");
        }
        
        // Generate new access token
        String newAccessToken = jwtTokenProvider.generateAccessToken(
            userId, 
            user.getUsername(), 
            rbacService.getUserPermissions(user),
            user.requiresMfa(), // MFA verified if required
            user.requiresMfa()  // MFA required
        );
        
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", newAccessToken);
        result.put("tokenType", "access");
        
        return result;
    }
}