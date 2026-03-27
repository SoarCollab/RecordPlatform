package cn.flying.service.integrity;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for IntegrityCheckService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IntegrityCheckService Tests")
class IntegrityCheckServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private IntegrityAlertMapper integrityAlertMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @InjectMocks
    private IntegrityCheckService integrityCheckService;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 100L;

    /**
     * Initialize MyBatis-Plus lambda cache to avoid LambdaQueryWrapper failures in pure Mockito tests.
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, IntegrityAlert.class);
    }

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        ReflectionTestUtils.setField(integrityCheckService, "sampleRate", 1.0); // 100% for tests
        ReflectionTestUtils.setField(integrityCheckService, "batchSize", 50);
        ReflectionTestUtils.setField(integrityCheckService, "lockTimeoutSeconds", 1800L);

        when(redissonClient.getLock("integrity-check-lock")).thenReturn(rLock);
    }

    @Test
    @DisplayName("Happy path: all files match - no alerts created")
    void checkIntegrity_allFilesMatch_noAlerts() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        byte[] fileContent = "hello world".getBytes();
        String hash = computeHash(fileContent);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectList(any())).thenReturn(List.of(file));

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(fileContent));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", hash, "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(1, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    @Test
    @DisplayName("Hash mismatch detected - alert created and SSE sent")
    void checkIntegrity_hashMismatch_alertCreated() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        byte[] fileContent = "hello world".getBytes();
        String expectedHash = "wrong_hash_value";

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(expectedHash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectList(any())).thenReturn(List.of(file));

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(fileContent));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        ArgumentCaptor<IntegrityAlert> alertCaptor = ArgumentCaptor.forClass(IntegrityAlert.class);
        verify(integrityAlertMapper).insert(alertCaptor.capture());
        IntegrityAlert alert = alertCaptor.getValue();
        assertEquals(IntegrityAlert.AlertType.HASH_MISMATCH.name(), alert.getAlertType());
        assertEquals(expectedHash, alert.getFileHash());

        verify(sseEmitterManager).broadcastToAdmins(eq(TENANT_ID), any(SseEvent.class));
    }

    @Test
    @DisplayName("File not found in S3 - FILE_NOT_FOUND alert")
    void checkIntegrity_fileNotFound_alert() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectList(any())).thenReturn(List.of(file));

        // Return empty result from storage
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of());
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        ArgumentCaptor<IntegrityAlert> alertCaptor = ArgumentCaptor.forClass(IntegrityAlert.class);
        verify(integrityAlertMapper).insert(alertCaptor.capture());
        assertEquals(IntegrityAlert.AlertType.FILE_NOT_FOUND.name(), alertCaptor.getValue().getAlertType());
    }

    @Test
    @DisplayName("Chain record not found - CHAIN_NOT_FOUND alert")
    void checkIntegrity_chainNotFound_alert() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        byte[] fileContent = "hello world".getBytes();
        String hash = computeHash(fileContent);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectList(any())).thenReturn(List.of(file));

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(fileContent));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        // Chain returns failure
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.GET_USER_FILE_ERROR, null);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        ArgumentCaptor<IntegrityAlert> alertCaptor = ArgumentCaptor.forClass(IntegrityAlert.class);
        verify(integrityAlertMapper).insert(alertCaptor.capture());
        assertEquals(IntegrityAlert.AlertType.CHAIN_NOT_FOUND.name(), alertCaptor.getValue().getAlertType());
    }

    @Test
    @DisplayName("Lock acquisition failure - skip check gracefully")
    void checkIntegrity_lockFailed_skips() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(false);

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(0, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());
        verify(fileMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("Empty file list - no-op")
    void checkIntegrity_emptyFileList_noop() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));
        when(fileMapper.selectList(any())).thenReturn(List.of());

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(0, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    @Test
    @DisplayName("Acknowledge alert updates status")
    void acknowledgeAlert_updatesStatus() {
        IntegrityAlert alert = new IntegrityAlert()
                .setStatus(IntegrityAlert.AlertStatus.PENDING.getCode());
        when(integrityAlertMapper.selectById(1L)).thenReturn(alert);
        when(integrityAlertMapper.updateById(any(IntegrityAlert.class))).thenReturn(1);

        integrityCheckService.acknowledgeAlert(1L, 99L);

        assertEquals(IntegrityAlert.AlertStatus.ACKNOWLEDGED.getCode(), alert.getStatus());
        verify(integrityAlertMapper).updateById(alert);
    }

    @Test
    @DisplayName("Acknowledge non-existent alert throws exception")
    void acknowledgeAlert_notFound_throws() {
        when(integrityAlertMapper.selectById(999L)).thenReturn(null);

        assertThrows(GeneralException.class, () ->
                integrityCheckService.acknowledgeAlert(999L, 99L));
    }

    @Test
    @DisplayName("Resolve alert updates status, resolvedBy, resolvedAt, and note")
    void resolveAlert_updatesFields() {
        IntegrityAlert alert = new IntegrityAlert()
                .setStatus(IntegrityAlert.AlertStatus.ACKNOWLEDGED.getCode());
        when(integrityAlertMapper.selectById(1L)).thenReturn(alert);
        when(integrityAlertMapper.updateById(any(IntegrityAlert.class))).thenReturn(1);

        integrityCheckService.resolveAlert(1L, 99L, "Fixed by re-upload");

        assertEquals(IntegrityAlert.AlertStatus.RESOLVED.getCode(), alert.getStatus());
        assertEquals(99L, alert.getResolvedBy());
        assertNotNull(alert.getResolvedAt());
        assertEquals("Fixed by re-upload", alert.getNote());
        verify(integrityAlertMapper).updateById(alert);
    }

    @Test
    @DisplayName("Resolve non-existent alert throws exception")
    void resolveAlert_notFound_throws() {
        when(integrityAlertMapper.selectById(999L)).thenReturn(null);

        assertThrows(GeneralException.class, () ->
                integrityCheckService.resolveAlert(999L, 99L, "note"));
    }

    @Test
    @DisplayName("No active tenants - returns zero stats")
    void checkIntegrity_noTenants_returnsZero() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of());

        var stats = integrityCheckService.checkIntegrity();

        assertEquals(0, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());
    }

    private String computeHash(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        return HexFormat.of().formatHex(hash);
    }
}
