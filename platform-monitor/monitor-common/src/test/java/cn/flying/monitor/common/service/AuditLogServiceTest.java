package cn.flying.monitor.common.service;

import cn.flying.monitor.common.entity.AuditLog;
import cn.flying.monitor.common.mapper.AuditLogMapper;
import cn.flying.monitor.common.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for audit logging service functionality
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {
    
    @Mock
    private AuditLogMapper auditLogMapper;
    
    @InjectMocks
    private AuditLogServiceImpl auditLogService;
    
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(auditLogService, "signingKey", "test-signing-key");
    }
    
    @Test
    void testLogAction_CreatesAuditLogWithSignature() {
        Map<String, Object> details = Map.of("action", "test", "value", 123);
        
        auditLogService.logAction(1L, AuditLog.Actions.LOGIN, AuditLog.ResourceTypes.USER, 
            "user1", details, "192.168.1.1", "Mozilla/5.0", "corr-123");
        
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        
        AuditLog capturedLog = captor.getValue();
        assertEquals(1L, capturedLog.getUserId());
        assertEquals(AuditLog.Actions.LOGIN, capturedLog.getAction());
        assertEquals(AuditLog.ResourceTypes.USER, capturedLog.getResourceType());
        assertEquals("user1", capturedLog.getResourceId());
        assertEquals("192.168.1.1", capturedLog.getIpAddress());
        assertEquals("Mozilla/5.0", capturedLog.getUserAgent());
        assertEquals("corr-123", capturedLog.getCorrelationId());
        assertNotNull(capturedLog.getSignature());
        assertNotNull(capturedLog.getTimestamp());
    }
    
    @Test
    void testLogSystemAction_CreatesAuditLogWithNullUser() {
        Map<String, Object> details = Map.of("system", "maintenance");
        
        auditLogService.logSystemAction("SYSTEM_MAINTENANCE", AuditLog.ResourceTypes.SYSTEM, 
            "system", details, "corr-456");
        
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        
        AuditLog capturedLog = captor.getValue();
        assertNull(capturedLog.getUserId());
        assertEquals("SYSTEM_MAINTENANCE", capturedLog.getAction());
        assertEquals("system", capturedLog.getIpAddress());
        assertEquals("system", capturedLog.getUserAgent());
    }
    
    @Test
    void testGetUserAuditLogs_CallsMapperWithCorrectParameters() {
        List<AuditLog> mockLogs = List.of(new AuditLog());
        when(auditLogMapper.findByUserId(1L, 10)).thenReturn(mockLogs);
        
        List<AuditLog> result = auditLogService.getUserAuditLogs(1L, 10);
        
        assertEquals(mockLogs, result);
        verify(auditLogMapper).findByUserId(1L, 10);
    }
    
    @Test
    void testGetAuditLogsByAction_CallsMapperWithCorrectParameters() {
        List<AuditLog> mockLogs = List.of(new AuditLog());
        when(auditLogMapper.findByAction(AuditLog.Actions.LOGIN, 5)).thenReturn(mockLogs);
        
        List<AuditLog> result = auditLogService.getAuditLogsByAction(AuditLog.Actions.LOGIN, 5);
        
        assertEquals(mockLogs, result);
        verify(auditLogMapper).findByAction(AuditLog.Actions.LOGIN, 5);
    }
    
    @Test
    void testGetAuditLogsByTimeRange_CallsMapperWithCorrectParameters() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        List<AuditLog> mockLogs = List.of(new AuditLog());
        when(auditLogMapper.findByTimeRange(start, end)).thenReturn(mockLogs);
        
        List<AuditLog> result = auditLogService.getAuditLogsByTimeRange(start, end);
        
        assertEquals(mockLogs, result);
        verify(auditLogMapper).findByTimeRange(start, end);
    }
    
    @Test
    void testGetAuditLogsByCorrelationId_CallsMapperWithCorrectParameters() {
        List<AuditLog> mockLogs = List.of(new AuditLog());
        when(auditLogMapper.findByCorrelationId("corr-123")).thenReturn(mockLogs);
        
        List<AuditLog> result = auditLogService.getAuditLogsByCorrelationId("corr-123");
        
        assertEquals(mockLogs, result);
        verify(auditLogMapper).findByCorrelationId("corr-123");
    }
    
    @Test
    void testVerifyLogIntegrity_WithValidSignature_ReturnsTrue() {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(1L);
        auditLog.setAction(AuditLog.Actions.LOGIN);
        auditLog.setResourceType(AuditLog.ResourceTypes.USER);
        auditLog.setResourceId("user1");
        auditLog.setDetails("{}");
        auditLog.setIpAddress("192.168.1.1");
        auditLog.setCorrelationId("corr-123");
        auditLog.setTimestamp(LocalDateTime.now());
        
        // First, log the action to get the signature
        auditLogService.logAction(1L, AuditLog.Actions.LOGIN, AuditLog.ResourceTypes.USER, 
            "user1", Map.of(), "192.168.1.1", "Mozilla/5.0", "corr-123");
        
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog loggedAuditLog = captor.getValue();
        
        // Verify the integrity of the logged audit log
        boolean isValid = auditLogService.verifyLogIntegrity(loggedAuditLog);
        assertTrue(isValid);
    }
    
    @Test
    void testGetSecurityStatistics_ReturnsCorrectStatistics() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.LOGIN, start, end)).thenReturn(10L);
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.LOGIN_FAILED, start, end)).thenReturn(2L);
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.ACCOUNT_LOCKED, start, end)).thenReturn(1L);
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.PERMISSION_DENIED, start, end)).thenReturn(3L);
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.MFA_SETUP, start, end)).thenReturn(5L);
        when(auditLogMapper.countByActionInTimeRange(AuditLog.Actions.PASSWORD_CHANGE, start, end)).thenReturn(2L);
        
        Map<String, Object> stats = auditLogService.getSecurityStatistics(start, end);
        
        assertEquals(10L, stats.get("loginAttempts"));
        assertEquals(2L, stats.get("failedLogins"));
        assertEquals(1L, stats.get("accountLocks"));
        assertEquals(3L, stats.get("permissionDenials"));
        assertEquals(5L, stats.get("mfaSetups"));
        assertEquals(2L, stats.get("passwordChanges"));
    }
    
    @Test
    void testExportAuditLogs_ReturnsLogsAndVerifiesIntegrity() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        
        AuditLog log1 = new AuditLog();
        log1.setSignature("valid-signature");
        AuditLog log2 = new AuditLog();
        log2.setSignature("invalid-signature");
        
        List<AuditLog> mockLogs = List.of(log1, log2);
        when(auditLogMapper.findByTimeRange(start, end)).thenReturn(mockLogs);
        
        List<AuditLog> result = auditLogService.exportAuditLogs(start, end, "json");
        
        assertEquals(2, result.size());
        verify(auditLogMapper).findByTimeRange(start, end);
    }
}