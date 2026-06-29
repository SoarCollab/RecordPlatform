package cn.flying.service.integrity;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.test.builders.BuilderResetExtension;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for three-tier integrity check implementation (P1-1).
 *
 * Tests the three check levels:
 * - LIGHTWEIGHT: Fast existence check without download (~90% savings)
 * - MEDIUM: Hash verification with DB comparison (~50% savings)
 * - HEAVY: Full blockchain consistency check (original behavior)
 *
 * Verifies cost reduction through different check strategies.
 */
@ExtendWith({MockitoExtension.class, BuilderResetExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IntegrityCheckService Three-Tier Tests (P1-1)")
class IntegrityCheckServiceThreeTierTest {

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
        ReflectionTestUtils.setField(integrityCheckService, "sampleRate", 1.0);
        ReflectionTestUtils.setField(integrityCheckService, "batchSize", 50);
        ReflectionTestUtils.setField(integrityCheckService, "lockTimeoutSeconds", 1800L);

        when(redissonClient.getLock("integrity-check-lock")).thenReturn(rLock);
    }

    // ========== LIGHTWEIGHT TESTS ==========

    @Test
    @DisplayName("Lightweight: Valid file exists - no download, no alert")
    void testLightweightCheck_ValidMetadata() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String hash = "abc123def456";
        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        Result<StorageObjectHeadVO> headResult = new Result<>(ResultEnum.SUCCESS, existingHead(hash, 1024L));
        when(fileRemoteClient.headObject(anyString(), anyString())).thenReturn(headResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertEquals(1, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        // Verify NO download occurred (getFileListByHash not called)
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFileUrlListByHash(anyList(), anyList());
        // Verify NO blockchain check occurred
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
        // No alert created
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    @Test
    @DisplayName("Lightweight: File missing - detects without download")
    void testLightweightCheck_FileMissing() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String hash = "missing_file_hash";
        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        Result<StorageObjectHeadVO> headResult = new Result<>(
                ResultEnum.SUCCESS,
                StorageObjectHeadVO.missing(buildFilePath(hash), hash, TENANT_ID)
        );
        when(fileRemoteClient.headObject(anyString(), anyString())).thenReturn(headResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        // Verify FILE_NOT_FOUND alert created
        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.FILE_NOT_FOUND.name())));

        // No download or blockchain check
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFileUrlListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
    }

    @Test
    @DisplayName("Lightweight: Size mismatch detected - alert created")
    void testLightweightCheck_SizeMismatch() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String hash = "size_mismatch_hash";
        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        Result<StorageObjectHeadVO> headResult = new Result<>(ResultEnum.SUCCESS, existingHead(hash, 2048L));
        when(fileRemoteClient.headObject(anyString(), anyString())).thenReturn(headResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.HASH_MISMATCH.name())
                        && "storage-size:2048".equals(alert.getChainHash())));
    }

    @Test
    @DisplayName("Lightweight: Metadata hash mismatch detected - alert created")
    void testLightweightCheck_MetadataHashMismatch() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String hash = "metadata_mismatch_hash";
        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        StorageObjectHeadVO head = new StorageObjectHeadVO(
                true,
                buildFilePath(hash),
                hash,
                TENANT_ID,
                TENANT_ID,
                "node1",
                1024L,
                "\"etag\"",
                "different_hash"
        );
        when(fileRemoteClient.headObject(anyString(), anyString()))
                .thenReturn(new Result<>(ResultEnum.SUCCESS, head));

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.HASH_MISMATCH.name())
                        && "different_hash".equals(alert.getChainHash())));
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
    }

    // ========== MEDIUM TESTS ==========

    @Test
    @DisplayName("Medium: Valid file with correct hash - no blockchain check")
    void testMediumCheck_ValidHash() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        // Create predictable content for SHA-256 hash verification
        String content = "test file content";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String expectedHash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(expectedHash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Medium check downloads file and verifies hash
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertEquals(1, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        // Verify download occurred
        verify(fileRemoteClient).getFileListByHash(anyList(), anyList());
        // Verify NO blockchain check (cost saving)
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    @Test
    @DisplayName("Medium: Hash mismatch detected - storage corruption")
    void testMediumCheck_HashMismatch() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String dbHash = "expected_hash_123";
        String content = "corrupted content";
        byte[] corruptedBytes = content.getBytes(StandardCharsets.UTF_8);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(dbHash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Return corrupted content that doesn't match dbHash
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(corruptedBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        // Verify HASH_MISMATCH alert with recomputed hash
        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) -> {
            boolean typeMatch = alert.getAlertType().equals(IntegrityAlert.AlertType.HASH_MISMATCH.name());
            boolean hashMatch = alert.getFileHash().equals(dbHash);
            boolean chainHashNotNull = alert.getChainHash() != null;
            return typeMatch && hashMatch && chainHashNotNull;
        }));

        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
    }

    @Test
    @DisplayName("Medium: Chunk validation - file not found")
    void testMediumCheck_ChunkValidation() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash("missing_chunks_hash");
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Return empty byte array (file not found)
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(new byte[0]));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.FILE_NOT_FOUND.name())));
    }

    // ========== HEAVY TESTS ==========

    @Test
    @DisplayName("Heavy: Full verification with blockchain - all consistent")
    void testHeavyCheck_FullConsistency() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String content = "blockchain verified content";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Storage returns valid content
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        // Blockchain returns matching hash
        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", hash,
                "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertEquals(1, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());
        assertEquals(0, stats.errorsEncountered());

        // Verify all three checks executed
        verify(fileRemoteClient).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient).getFile(String.valueOf(USER_ID), hash);
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    @Test
    @DisplayName("Heavy: Content hash verification - blockchain mismatch")
    void testHeavyCheck_ContentHashVerification() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String content = "local content";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String localHash = calculateSHA256(contentBytes);
        String chainHash = "different_blockchain_hash";

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(localHash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Storage has valid content matching local hash
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        // Blockchain has different hash
        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", chainHash,
                "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(localHash))).thenReturn(chainResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        // Verify HASH_MISMATCH alert with blockchain hash
        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.HASH_MISMATCH.name())
                && alert.getChainHash().equals(chainHash)));
    }

    @Test
    @DisplayName("Heavy: Blockchain record not found")
    void testHeavyCheck_ChainNotFound() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String content = "orphaned file";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Storage has file
        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        // Blockchain has no record
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.GET_USER_FILE_ERROR, null);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertEquals(1, stats.totalChecked());
        assertEquals(1, stats.mismatchesFound());

        verify(integrityAlertMapper).insert(argThat((IntegrityAlert alert) ->
                alert.getAlertType().equals(IntegrityAlert.AlertType.CHAIN_NOT_FOUND.name())));
    }

    // ========== DISTRIBUTION STRATEGY TESTS ==========

    @Test
    @DisplayName("Distribution: Default strategy uses HEAVY for backward compatibility")
    void testDistributionStrategy_DefaultHeavy() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String content = "default check";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", hash,
                "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        // Call without level parameter (default behavior)
        IntegrityCheckStatsVO stats = integrityCheckService.checkIntegrity();

        assertEquals(1, stats.totalChecked());
        assertEquals(0, stats.mismatchesFound());

        // Verify HEAVY check executed (blockchain called)
        verify(fileRemoteClient).getFile(anyString(), eq(hash));
    }

    @Test
    @DisplayName("Distribution: Cost comparison across check levels")
    void testDistributionStrategy_CostComparison() throws Exception {
        // This test demonstrates the cost savings of each tier
        // Simulates 100 files checked across different levels

        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));

        String content = "benchmark file";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        // Setup mocks for all levels
        Result<StorageObjectHeadVO> headResult = new Result<>(ResultEnum.SUCCESS, existingHead(hash, 1024L));
        when(fileRemoteClient.headObject(anyString(), anyString())).thenReturn(headResult);

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", hash,
                "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        // Test LIGHTWEIGHT
        reset(fileRemoteClient);
        when(fileRemoteClient.headObject(anyString(), anyString())).thenReturn(headResult);
        integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFileUrlListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());

        // Test MEDIUM
        reset(fileRemoteClient);
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);
        integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);
        verify(fileRemoteClient, times(1)).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());

        // Test HEAVY
        reset(fileRemoteClient);
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);
        integrityCheckService.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);
        verify(fileRemoteClient, times(1)).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, times(1)).getFile(anyString(), anyString());
    }

    @Test
    @DisplayName("Distribution: Manual check still uses HEAVY for full verification")
    void testDistributionStrategy_ManualCheckUsesHeavy() throws Exception {
        when(rLock.tryLock(0, 1800, TimeUnit.SECONDS)).thenReturn(true);

        String content = "manual check file";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = calculateSHA256(contentBytes);

        File file = FileTestBuilder.aFile(f -> {
            f.setTenantId(TENANT_ID);
            f.setUid(USER_ID);
            f.setFileHash(hash);
            f.setStatus(FileUploadStatus.SUCCESS.getCode());
        });

        when(fileMapper.selectPage(any(), any())).thenReturn(
                new Page<File>() {{ setRecords(List.of(file)); }});

        Result<List<byte[]>> storageResult = new Result<>(ResultEnum.SUCCESS, List.of(contentBytes));
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenReturn(storageResult);

        FileDetailVO chainDetail = new FileDetailVO(
                String.valueOf(USER_ID), "test.txt", "{}", "", hash,
                "2025-01-01", 0L, 1024L, "text/plain");
        Result<FileDetailVO> chainResult = new Result<>(ResultEnum.SUCCESS, chainDetail);
        when(fileRemoteClient.getFile(anyString(), eq(hash))).thenReturn(chainResult);

        // Manual check should use HEAVY verification
        IntegrityCheckStatsVO stats = integrityCheckService.triggerManualCheck(TENANT_ID);

        assertEquals(1, stats.totalChecked());
        verify(fileRemoteClient).getFile(anyString(), eq(hash));
    }

    // ========== Helper Methods ==========

    /**
     * Build a successful storage HEAD response for lightweight integrity checks.
     */
    private StorageObjectHeadVO existingHead(String hash, long contentLength) {
        return new StorageObjectHeadVO(
                true,
                buildFilePath(hash),
                hash,
                TENANT_ID,
                TENANT_ID,
                "node1",
                contentLength,
                "\"etag\"",
                hash
        );
    }

    /**
     * Build the tenant-scoped logical file path used by IntegrityCheckService.
     */
    private String buildFilePath(String hash) {
        return String.format("storage/tenant/%d/chunk/%s", TENANT_ID, hash);
    }

    /**
     * Calculate SHA-256 hash matching the implementation in IntegrityCheckService.
     */
    private String calculateSHA256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return bytesToHex(hashBytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
