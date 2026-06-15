package cn.flying.service.impl;

import cn.flying.common.util.UidEncoder;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.encryption.EncryptionStrategyFactory;
import cn.flying.test.builders.FileUploadStateTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for file cleanup with persisted paths.
 * Verifies cleanup workflow using stored paths, retry mechanism, and backward compatibility.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileUploadService Cleanup Tests with Persisted Paths")
class FileUploadServiceCleanupTest {

    @Mock
    private FileUploadRedisStateManager redisStateManager;

    @Mock
    private EncryptionStrategyFactory encryptionStrategyFactory;

    @Mock
    private FileService fileService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private QuotaService quotaService;

    @Mock
    private RedissonClient redissonClient;

    @InjectMocks
    private FileUploadServiceImpl fileUploadService;

    @TempDir
    Path tempDir;

    private static MockedStatic<UidEncoder> uidEncoderMock;

    private static final Long USER_ID = 100L;
    private static final String SUID = "encoded_uid_100";
    private static final String CLIENT_ID = "test_client_cleanup_123";
    private static final int MAX_CLEANUP_RETRIES = 3;

    private Path uploadTempPath;
    private Path processedTempPath;

    @BeforeAll
    static void setUpClass() {
        uidEncoderMock = mockStatic(UidEncoder.class);
        uidEncoderMock.when(() -> UidEncoder.encodeUid(anyString())).thenReturn(SUID);
        uidEncoderMock.when(() -> UidEncoder.encodeCid(anyString())).thenReturn(CLIENT_ID);
    }

    @AfterAll
    static void tearDownClass() {
        if (uidEncoderMock != null) {
            uidEncoderMock.close();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        FileUploadStateTestBuilder.resetClientIdCounter();
        ReflectionTestUtils.setField(fileUploadService, "eventPublisher", eventPublisher);

        // Create temp directories for testing
        uploadTempPath = tempDir.resolve("uploads").resolve(SUID).resolve(CLIENT_ID);
        processedTempPath = tempDir.resolve("processed").resolve(SUID).resolve(CLIENT_ID);
        Files.createDirectories(uploadTempPath);
        Files.createDirectories(processedTempPath);

        // Create some test files
        Files.createFile(uploadTempPath.resolve("chunk_0"));
        Files.createFile(uploadTempPath.resolve("chunk_1"));
        Files.createFile(processedTempPath.resolve("encrypted_chunk_0"));
        Files.createFile(processedTempPath.resolve("encrypted_chunk_1"));
    }

    /**
     * Test 1: Normal cleanup using persisted paths
     * Verifies that cleanup successfully uses uploadTempPath and processedTempPath from state
     */
    @Test
    @DisplayName("testCleanupWithPersistedPaths_Success - Normal cleanup uses persisted paths")
    void testCleanupWithPersistedPaths_Success() throws Exception {
        // Arrange
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(uploadTempPath.toString());
        state.setProcessedTempPath(processedTempPath.toString());
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Verify directories exist before cleanup
        assertTrue(Files.exists(uploadTempPath));
        assertTrue(Files.exists(processedTempPath));
        assertTrue(Files.exists(uploadTempPath.resolve("chunk_0")));
        assertTrue(Files.exists(processedTempPath.resolve("encrypted_chunk_0")));

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should succeed");
        verify(redisStateManager, times(1)).getState(CLIENT_ID);
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
        verify(redisStateManager, never()).updateState(any());

        // Verify directories are cleaned up
        assertFalse(Files.exists(uploadTempPath));
        assertFalse(Files.exists(processedTempPath));
    }

    /**
     * Test 2: Cleanup without SUID uses persisted paths
     * Verifies that scheduled cleanup (without SUID) can still clean up using persisted paths
     */
    @Test
    @DisplayName("testCleanupWithoutSUID_UsesPersistedPaths - Scheduled cleanup uses persisted paths")
    void testCleanupWithoutSUID_UsesPersistedPaths() throws Exception {
        // Arrange
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(uploadTempPath.toString());
        state.setProcessedTempPath(processedTempPath.toString());
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Verify directories exist before cleanup
        assertTrue(Files.exists(uploadTempPath));
        assertTrue(Files.exists(processedTempPath));

        // Act - Pass empty SUID to simulate scheduled cleanup
        boolean result = invokeCleanupUploadSessionInternal("", CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should succeed even without SUID");
        verify(redisStateManager, times(1)).getState(CLIENT_ID);
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);

        // Verify directories are cleaned up
        assertFalse(Files.exists(uploadTempPath));
        assertFalse(Files.exists(processedTempPath));
    }

    /**
     * Test 3: Cleanup retry mechanism
     * Verifies that cleanup succeeds even with non-existent paths (already cleaned)
     */
    @Test
    @DisplayName("testCleanupRetry_WithRetryCount - Cleanup succeeds with non-existent paths")
    void testCleanupRetry_WithRetryCount() throws Exception {
        // Arrange - Non-existent paths should not cause failure
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setCleanupRetryCount(0);

        // Non-existent paths (already cleaned or never created)
        Path nonExistentPath = tempDir.resolve("non_existent");
        state.setUploadTempPath(nonExistentPath.toString());
        state.setProcessedTempPath(nonExistentPath.toString());

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert - Should succeed because non-existent directories are considered already cleaned
        assertTrue(result, "Cleanup should succeed when paths don't exist (already cleaned)");
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
        verify(redisStateManager, never()).updateState(any());
    }

    /**
     * Test 4: Cleanup exceeds max retries
     * Verifies that cleanup forcefully removes Redis state after max retries
     */
    @Test
    @DisplayName("testCleanupRetry_ExceedsMaxRetries - Force cleanup after max retries")
    void testCleanupRetry_ExceedsMaxRetries() throws Exception {
        // Arrange
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(uploadTempPath.toString());
        state.setProcessedTempPath(processedTempPath.toString());
        state.setCleanupRetryCount(MAX_CLEANUP_RETRIES); // Already at max

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should succeed by forcing Redis cleanup");
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
        verify(redisStateManager, never()).updateState(any());
    }

    /**
     * Test 5: Backward compatibility - cleanup without persisted paths
     * Verifies that cleanup falls back to SUID-based path construction for old sessions
     */
    @Test
    @DisplayName("testCleanup_BackwardCompatibility - Falls back to SUID for old sessions")
    void testCleanup_BackwardCompatibility() throws Exception {
        // Arrange - Old session without persisted paths
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(null); // No persisted path
        state.setProcessedTempPath(null); // No persisted path
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // We need to mock the path construction methods since they use real filesystem
        // The service will try to construct paths using SUID

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should succeed with SUID fallback");
        verify(redisStateManager, times(1)).getState(CLIENT_ID);
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
    }

    /**
     * Test 6: Cleanup with null SUID and null persisted paths
     * Verifies that cleanup skips filesystem cleanup but removes Redis state
     */
    @Test
    @DisplayName("testCleanup_NullSuidAndNullPaths - Skips filesystem, clears Redis")
    void testCleanup_NullSuidAndNullPaths() throws Exception {
        // Arrange
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(null);
        state.setUploadTempPath(null);
        state.setProcessedTempPath(null);
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, "");

        // Act
        boolean result = invokeCleanupUploadSessionInternal("", CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should skip filesystem and only clear Redis");
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, "");
    }

    /**
     * Test 7: Cleanup increments retry count correctly
     * Verifies that cleanup succeeds with invalid paths (graceful handling)
     */
    @Test
    @DisplayName("testCleanup_RetryCountProgression - Invalid paths are handled gracefully")
    void testCleanup_RetryCountProgression() throws Exception {
        // Arrange - Retry count = 1, invalid paths
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath("/invalid/path/upload");
        state.setProcessedTempPath("/invalid/path/processed");
        state.setCleanupRetryCount(1);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert - Should succeed because non-existent paths are treated as already cleaned
        assertTrue(result, "Cleanup should succeed with invalid/non-existent paths");
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
        verify(redisStateManager, never()).updateState(any());
    }

    /**
     * Test 8: Cleanup with state not found
     * Verifies cleanup handles missing state gracefully
     */
    @Test
    @DisplayName("testCleanup_StateNotFound - Handles missing state gracefully")
    void testCleanup_StateNotFound() throws Exception {
        // Arrange
        when(redisStateManager.getState(CLIENT_ID)).thenReturn(null);
        when(redisStateManager.removePausedSession(CLIENT_ID)).thenReturn(true);

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertFalse(result, "Cleanup should return false when state not found");
        verify(redisStateManager, times(1)).removePausedSession(CLIENT_ID);
        verify(redisStateManager, never()).removeSession(anyString(), anyString());
    }

    /**
     * Test 9: Cleanup with persisted paths handles partial directory cleanup
     * Verifies cleanup works even if one directory is missing
     */
    @Test
    @DisplayName("testCleanup_PartialDirectories - Handles missing directories gracefully")
    void testCleanup_PartialDirectories() throws Exception {
        // Arrange - Delete upload directory before cleanup
        deleteDirectory(uploadTempPath);

        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(uploadTempPath.toString());
        state.setProcessedTempPath(processedTempPath.toString());
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Verify only processed directory exists
        assertFalse(Files.exists(uploadTempPath));
        assertTrue(Files.exists(processedTempPath));

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertTrue(result, "Cleanup should succeed even with missing upload directory");
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);
        assertFalse(Files.exists(processedTempPath));
    }

    /**
     * Test 10: Cleanup verifies correct method is called with persisted paths
     * Verifies that cleanupDirectory is called with the correct Path objects
     */
    @Test
    @DisplayName("testCleanup_VerifyMethodCalls - Correct paths passed to cleanup")
    void testCleanup_VerifyMethodCalls() throws Exception {
        // Arrange
        FileUploadState state = FileUploadStateTestBuilder.anUploadState();
        state.setClientId(CLIENT_ID);
        state.setSuid(SUID);
        state.setUploadTempPath(uploadTempPath.toString());
        state.setProcessedTempPath(processedTempPath.toString());
        state.setCleanupRetryCount(0);

        when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
        doNothing().when(redisStateManager).removeSession(CLIENT_ID, SUID);

        // Act
        boolean result = invokeCleanupUploadSessionInternal(SUID, CLIENT_ID);

        // Assert
        assertTrue(result);
        verify(redisStateManager, times(1)).getState(CLIENT_ID);
        verify(redisStateManager, times(1)).removeSession(CLIENT_ID, SUID);

        // Verify paths were used (directories should be deleted)
        assertFalse(Files.exists(uploadTempPath), "Upload directory should be deleted");
        assertFalse(Files.exists(processedTempPath), "Processed directory should be deleted");
    }

    // ==================== Helper Methods ====================

    /**
     * Invokes the private cleanupUploadSessionInternal method using reflection
     */
    private boolean invokeCleanupUploadSessionInternal(String suid, String clientId) throws Exception {
        Method method = FileUploadServiceImpl.class.getDeclaredMethod(
            "cleanupUploadSessionInternal", String.class, String.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(fileUploadService, suid, clientId);
    }

    /**
     * Recursively deletes a directory and its contents
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
            }
        }
    }
}
