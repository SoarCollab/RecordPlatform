package cn.flying.monitor.common.security;

import cn.flying.monitor.common.exception.CertificateValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for Certificate Authentication Service
 * This demonstrates the complete workflow of certificate management
 */
class CertificateAuthenticationIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void testCertificateLifecycle() {
        // Setup mock Redis template
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        
        CertificateAuthenticationService service = new CertificateAuthenticationService(redisTemplate);
        
        // Test input validation
        assertThrows(CertificateValidationException.class, 
            () -> service.registerClientCertificate(null, "cert", "desc"));
        
        assertThrows(CertificateValidationException.class, 
            () -> service.registerClientCertificate("client", null, "desc"));
        
        assertThrows(CertificateValidationException.class, 
            () -> service.registerClientCertificate("client", "invalid-cert", "desc"));
        
        // Test certificate validation
        assertFalse(service.validateClientCertificate(null, "cert"));
        assertFalse(service.validateClientCertificate("", "cert"));
        assertFalse(service.validateClientCertificate("client", "cert"));
        
        // Test revocation validation
        assertThrows(CertificateValidationException.class, 
            () -> service.revokeCertificate(null, "reason"));
        
        assertThrows(CertificateValidationException.class, 
            () -> service.revokeCertificate("client", null));
        
        // Test blacklist functionality
        assertFalse(service.isCertificateBlacklisted(null));
        assertFalse(service.isCertificateBlacklisted(""));
        
        // Test certificate info retrieval
        assertThrows(CertificateValidationException.class, 
            () -> service.getClientCertificateInfo(null));
        
        // Test getting all active certificates
        Map<String, CertificateAuthenticationService.ClientCertificateInfo> activeCerts = 
            service.getAllActiveCertificates();
        assertNotNull(activeCerts);
        assertTrue(activeCerts.isEmpty());
        
        System.out.println("Certificate Authentication Service integration test completed successfully!");
    }
}