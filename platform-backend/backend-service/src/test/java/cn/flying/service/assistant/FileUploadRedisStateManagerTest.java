package cn.flying.service.assistant;

import cn.flying.common.util.CacheUtils;
import cn.flying.dao.vo.file.FileUploadState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FileUploadRedisStateManager 单元测试类
 * 测试Redis状态管理器的所有功能
 */
@ExtendWith(MockitoExtension.class)
class FileUploadRedisStateManagerTest {

    private static final String TEST_SESSION_ID = "test-session-123";

    private static final String TEST_SUID = "test-suid-456";

    private static final String TEST_FILE_NAME = "test-file.pdf";

    private static final long DEFAULT_SESSION_EXPIRE_SECONDS = 24 * 60 * 60;

    @InjectMocks
    private FileUploadRedisStateManager stateManager;

    @Mock
    private CacheUtils cacheUtils;

    private FileUploadState testState;

    @BeforeEach
    void setUp() {
        testState = new FileUploadState(
                TEST_FILE_NAME,
                1024 * 1024L,  // 1MB
                "application/pdf",
                TEST_SESSION_ID,
                1024,  // chunkSize
                10     // totalChunks
        );
    }

    // ===== 状态保存与获取测试组 =====

    @Test
    void saveNewState_保存完整状态信息() {
        // 执行保存
        stateManager.saveNewState(testState, TEST_SUID);

        // 验证保存状态
        String expectedKey = "file:upload:session:" + TEST_SESSION_ID;
        verify(cacheUtils).saveToCache(eq(expectedKey), eq(testState), eq(DEFAULT_SESSION_EXPIRE_SECONDS));
    }

    @Test
    void saveNewState_添加到活跃会话集合() {
        stateManager.saveNewState(testState, TEST_SUID);

        // 验证添加到活跃会话集合
        verify(cacheUtils).setAdd("file:upload:active_sessions", TEST_SESSION_ID);
    }

    @Test
    void saveNewState_保存文件名映射() {
        stateManager.saveNewState(testState, TEST_SUID);

        // 验证文件名映射
        String expectedFileClientKey = TEST_FILE_NAME + "_" + TEST_SUID;
        verify(cacheUtils).hashPut("file:upload:filename_suid_mapping", expectedFileClientKey, TEST_SESSION_ID);
    }

    @Test
    void saveNewState_初始化分片集合() {
        stateManager.saveNewState(testState, TEST_SUID);

        // 验证设置分片集合的过期时间
        verify(cacheUtils).setExpire(
                "file:upload:uploaded_chunks:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
        verify(cacheUtils).setExpire(
                "file:upload:processed_chunks:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Test
    void getState_获取存在的状态() {
        // 模拟Redis返回状态
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);
        when(cacheUtils.getIntegerSet(anyString())).thenReturn(new HashSet<>());
        when(cacheUtils.hashGetAll(anyString())).thenReturn(new HashMap<>());

        // 执行获取
        FileUploadState result = stateManager.getState(TEST_SESSION_ID);

        // 验证结果
        assertThat(result).isNotNull();
        assertThat(result.getClientId()).isEqualTo(TEST_SESSION_ID);
        assertThat(result.getFileName()).isEqualTo(TEST_FILE_NAME);

        // 验证调用了填充集合方法
        verify(cacheUtils).getIntegerSet("file:upload:uploaded_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).getIntegerSet("file:upload:processed_chunks:" + TEST_SESSION_ID);
    }

    @Test
    void getState_状态不存在返回null() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(null);

        FileUploadState result = stateManager.getState(TEST_SESSION_ID);

        assertThat(result).isNull();
        // 不应该尝试填充集合
        verify(cacheUtils, never()).getIntegerSet(anyString());
    }

    @Test
    void updateState_更新状态信息() {
        // 准备测试数据
        testState.getUploadedChunks().addAll(Set.of(0, 1, 2));
        testState.getProcessedChunks().addAll(Set.of(0, 1));
        testState.getChunkHashes().put("chunk_0", "hash0");
        testState.getKeys().put(0, "key0".getBytes());

        // 执行更新
        stateManager.updateState(testState);

        // 验证基本状态更新
        verify(cacheUtils).saveToCache(
                "file:upload:session:" + TEST_SESSION_ID,
                testState,
                DEFAULT_SESSION_EXPIRE_SECONDS
        );

        // 验证分片集合更新
        verify(cacheUtils).setAddIntegers(
                "file:upload:uploaded_chunks:" + TEST_SESSION_ID,
                testState.getUploadedChunks()
        );
        verify(cacheUtils).setAddIntegers(
                "file:upload:processed_chunks:" + TEST_SESSION_ID,
                testState.getProcessedChunks()
        );

        // 验证哈希和密钥更新
        verify(cacheUtils).hashPutAll(
                "file:upload:chunk_hashes:" + TEST_SESSION_ID,
                testState.getChunkHashes()
        );
    }

    // ===== 分片管理测试组 =====

    @Test
    void addUploadedChunk_添加上传分片() {
        int chunkNumber = 5;

        stateManager.addUploadedChunk(TEST_SESSION_ID, chunkNumber);

        // 验证添加分片
        verify(cacheUtils).setAdd(
                "file:upload:uploaded_chunks:" + TEST_SESSION_ID,
                String.valueOf(chunkNumber)
        );

        // 验证更新活动时间
        verify(cacheUtils).takeFormCache(anyString(), eq(FileUploadState.class));
    }

    @Test
    void addProcessedChunk_添加处理分片() {
        int chunkNumber = 3;

        stateManager.addProcessedChunk(TEST_SESSION_ID, chunkNumber);

        // 验证添加处理分片
        verify(cacheUtils).setAdd(
                "file:upload:processed_chunks:" + TEST_SESSION_ID,
                String.valueOf(chunkNumber)
        );

        // 验证更新活动时间
        verify(cacheUtils).takeFormCache(anyString(), eq(FileUploadState.class));
    }

    @Test
    void addChunkHash_保存分片哈希() {
        String chunkKey = "chunk_0";
        String hash = "abc123hash";

        stateManager.addChunkHash(TEST_SESSION_ID, chunkKey, hash);

        verify(cacheUtils).hashPut(
                "file:upload:chunk_hashes:" + TEST_SESSION_ID,
                chunkKey,
                hash
        );
    }

    @Test
    void addChunkKey_保存分片密钥() {
        int chunkNumber = 0;
        byte[] keyData = "test-key-data".getBytes();

        stateManager.addChunkKey(TEST_SESSION_ID, chunkNumber, keyData);

        verify(cacheUtils).hashPut(
                "file:upload:chunk_keys:" + TEST_SESSION_ID,
                String.valueOf(chunkNumber),
                keyData
        );
    }

    @Test
    void getChunkKeys_获取所有密钥() {
        // 模拟Redis返回的密钥数据
        Map<Object, Object> mockEntries = new HashMap<>();
        mockEntries.put("0", "key0");
        mockEntries.put("1", "key1");
        mockEntries.put("2", "key2");

        when(cacheUtils.hashGetAll("file:upload:chunk_keys:" + TEST_SESSION_ID))
                .thenReturn(mockEntries);
        when(cacheUtils.hashGet(anyString(), eq("0"), eq(byte[].class)))
                .thenReturn("key0".getBytes());
        when(cacheUtils.hashGet(anyString(), eq("1"), eq(byte[].class)))
                .thenReturn("key1".getBytes());
        when(cacheUtils.hashGet(anyString(), eq("2"), eq(byte[].class)))
                .thenReturn("key2".getBytes());

        // 执行获取
        Map<Integer, byte[]> result = stateManager.getChunkKeys(TEST_SESSION_ID);

        // 验证结果
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo("key0".getBytes());
        assertThat(result.get(1)).isEqualTo("key1".getBytes());
        assertThat(result.get(2)).isEqualTo("key2".getBytes());
    }

    @Test
    void populateStateCollections_填充集合数据() {
        // 准备模拟数据
        Set<Integer> uploadedChunks = Set.of(0, 1, 2);
        Set<Integer> processedChunks = Set.of(0, 1);
        Map<Object, Object> chunkHashes = new HashMap<>();
        chunkHashes.put("chunk_0", "\"hash0\"");
        chunkHashes.put("chunk_1", "\"hash1\"");

        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);
        when(cacheUtils.getIntegerSet("file:upload:uploaded_chunks:" + TEST_SESSION_ID))
                .thenReturn(uploadedChunks);
        when(cacheUtils.getIntegerSet("file:upload:processed_chunks:" + TEST_SESSION_ID))
                .thenReturn(processedChunks);
        when(cacheUtils.hashGetAll("file:upload:chunk_hashes:" + TEST_SESSION_ID))
                .thenReturn(chunkHashes);
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:" + TEST_SESSION_ID))
                .thenReturn(new HashMap<>());

        // 执行获取，会触发填充
        FileUploadState result = stateManager.getState(TEST_SESSION_ID);

        // 验证集合被正确填充
        assertThat(result.getUploadedChunks()).containsExactlyInAnyOrderElementsOf(uploadedChunks);
        assertThat(result.getProcessedChunks()).containsExactlyInAnyOrderElementsOf(processedChunks);
    }

    // ===== 会话管理测试组 =====

    @Test
    void updateLastActivityTime_更新活动时间() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);

        long timeBefore = testState.getLastActivityTime();

        // 等待一小段时间确保时间变化
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // ignore
        }

        stateManager.updateLastActivityTime(TEST_SESSION_ID);

        // 验证状态被重新保存
        ArgumentCaptor<FileUploadState> stateCaptor = ArgumentCaptor.forClass(FileUploadState.class);
        verify(cacheUtils).saveToCache(
                eq("file:upload:session:" + TEST_SESSION_ID),
                stateCaptor.capture(),
                eq(DEFAULT_SESSION_EXPIRE_SECONDS)
        );

        // 验证时间被更新
        assertThat(stateCaptor.getValue().getLastActivityTime()).isGreaterThan(timeBefore);
    }

    @Test
    void addPausedSession_添加暂停会话() {
        stateManager.addPausedSession(TEST_SESSION_ID);

        verify(cacheUtils).setAdd("file:upload:paused_sessions", TEST_SESSION_ID);
    }

    @Test
    void removePausedSession_移除暂停会话() {
        when(cacheUtils.setIsMember("file:upload:paused_sessions", TEST_SESSION_ID))
                .thenReturn(true);

        boolean wasPaused = stateManager.removePausedSession(TEST_SESSION_ID);

        assertThat(wasPaused).isTrue();
        verify(cacheUtils).setRemove("file:upload:paused_sessions", TEST_SESSION_ID);
    }

    @Test
    void isSessionPaused_检查暂停状态() {
        when(cacheUtils.setIsMember("file:upload:paused_sessions", TEST_SESSION_ID))
                .thenReturn(true);

        boolean isPaused = stateManager.isSessionPaused(TEST_SESSION_ID);

        assertThat(isPaused).isTrue();
    }

    @Test
    void getSessionIdByFileClientKey_根据文件名获取会话() {
        String expectedFileClientKey = TEST_FILE_NAME + "_" + TEST_SUID;
        when(cacheUtils.hashGet("file:upload:filename_suid_mapping", expectedFileClientKey, String.class))
                .thenReturn(TEST_SESSION_ID);

        String result = stateManager.getSessionIdByFileClientKey(TEST_FILE_NAME, TEST_SUID);

        assertThat(result).isEqualTo(TEST_SESSION_ID);
    }

    @Test
    void getAllActiveSessionIds_获取所有活跃会话() {
        Set<String> mockSessions = Set.of("session1", "session2", "session3");
        when(cacheUtils.setMembers("file:upload:active_sessions")).thenReturn(mockSessions);

        Set<String> result = stateManager.getAllActiveSessionIds();

        assertThat(result).containsExactlyInAnyOrderElementsOf(mockSessions);
    }

    @Test
    void getAllActiveSessionIds_返回空集合当无会话() {
        when(cacheUtils.setMembers("file:upload:active_sessions")).thenReturn(null);

        Set<String> result = stateManager.getAllActiveSessionIds();

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void removeSession_完整清理会话() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);

        stateManager.removeSession(TEST_SESSION_ID, TEST_SUID);

        // 验证删除映射
        String expectedFileClientKey = TEST_FILE_NAME + "_" + TEST_SUID;
        verify(cacheUtils).hashDelete("file:upload:filename_suid_mapping", expectedFileClientKey);

        // 验证删除分片集合
        verify(cacheUtils).deleteCache("file:upload:uploaded_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:processed_chunks:" + TEST_SESSION_ID);

        // 验证删除哈希和密钥
        verify(cacheUtils).deleteCache("file:upload:chunk_hashes:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:chunk_keys:" + TEST_SESSION_ID);

        // 验证删除状态
        verify(cacheUtils).deleteCache("file:upload:session:" + TEST_SESSION_ID);

        // 验证从活跃会话和暂停会话中移除
        verify(cacheUtils).setRemove("file:upload:active_sessions", TEST_SESSION_ID);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", TEST_SESSION_ID);
    }

    @Test
    void removeSession_状态不存在时跳过() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(null);

        stateManager.removeSession(TEST_SESSION_ID, TEST_SUID);

        // 不应该执行任何删除操作
        verify(cacheUtils, never()).hashDelete(anyString(), anyString());
        verify(cacheUtils, never()).deleteCache(anyString());
        verify(cacheUtils, never()).setRemove(anyString(), anyString());
    }

    @Test
    void removeSessionByFileName_根据文件名清理() {
        // 使用 MockedStatic 来模拟 UidEncoder
        try (var mockedStatic = mockStatic(cn.flying.common.util.UidEncoder.class)) {
            mockedStatic.when(() -> cn.flying.common.util.UidEncoder.encodeUid("user123"))
                    .thenReturn(TEST_SUID);

            String expectedFileClientKey = TEST_FILE_NAME + "_" + TEST_SUID;
            when(cacheUtils.hashGet("file:upload:filename_suid_mapping", expectedFileClientKey, String.class))
                    .thenReturn(TEST_SESSION_ID);
            when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);

            stateManager.removeSessionByFileName("user123", TEST_FILE_NAME);

            // 验证先查找会话ID
            verify(cacheUtils).hashGet("file:upload:filename_suid_mapping", expectedFileClientKey, String.class);

            // 验证删除会话
            verify(cacheUtils).deleteCache("file:upload:session:" + TEST_SESSION_ID);
        }
    }

    // ===== Redis操作测试组 =====

    @Test
    void 测试键前缀构建() {
        // 通过调用各种操作来间接测试键构建
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);
        when(cacheUtils.getIntegerSet(anyString())).thenReturn(new HashSet<>());
        when(cacheUtils.hashGetAll(anyString())).thenReturn(new HashMap<>());

        stateManager.getState(TEST_SESSION_ID);

        // 验证使用了正确的键前缀
        verify(cacheUtils).takeFormCache("file:upload:session:" + TEST_SESSION_ID, FileUploadState.class);
        verify(cacheUtils).getIntegerSet("file:upload:uploaded_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).getIntegerSet("file:upload:processed_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).hashGetAll("file:upload:chunk_hashes:" + TEST_SESSION_ID);
        verify(cacheUtils).hashGetAll("file:upload:chunk_keys:" + TEST_SESSION_ID);
    }

    @Test
    void 测试过期时间设置() {
        stateManager.updateState(testState);

        // 验证所有缓存都设置了过期时间
        verify(cacheUtils).setExpire(
                "file:upload:uploaded_chunks:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
        verify(cacheUtils).setExpire(
                "file:upload:processed_chunks:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
        verify(cacheUtils).setExpire(
                "file:upload:chunk_hashes:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
        verify(cacheUtils).setExpire(
                "file:upload:chunk_keys:" + TEST_SESSION_ID,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Test
    void 测试集合操作() {
        Set<Integer> testSet = Set.of(1, 2, 3, 4, 5);
        testState.getUploadedChunks().addAll(testSet);

        stateManager.updateState(testState);

        // 验证集合操作
        verify(cacheUtils).setAddIntegers(
                "file:upload:uploaded_chunks:" + TEST_SESSION_ID,
                testSet
        );
    }

    @Test
    void 测试哈希操作() {
        Map<String, String> testHashes = new HashMap<>();
        testHashes.put("chunk_0", "hash0");
        testHashes.put("chunk_1", "hash1");
        testHashes.put("chunk_2", "hash2");
        testState.getChunkHashes().putAll(testHashes);

        stateManager.updateState(testState);

        // 验证哈希操作
        verify(cacheUtils).hashPutAll(
                "file:upload:chunk_hashes:" + TEST_SESSION_ID,
                testHashes
        );
    }

    @Test
    void 测试删除操作() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);

        stateManager.removeSession(TEST_SESSION_ID, TEST_SUID);

        // 验证所有删除操作都被调用
        verify(cacheUtils).deleteCache("file:upload:session:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:uploaded_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:processed_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:chunk_hashes:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:chunk_keys:" + TEST_SESSION_ID);
    }

    // ===== 异常处理测试组 =====

    @Test
    void 处理序列化异常_返回原始值() {
        // 模拟带有错误JSON的哈希数据
        Map<Object, Object> chunkHashes = new HashMap<>();
        chunkHashes.put("chunk_0", "invalid-json-{{}");  // 无效的JSON

        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);
        when(cacheUtils.getIntegerSet(anyString())).thenReturn(new HashSet<>());
        when(cacheUtils.hashGetAll("file:upload:chunk_hashes:" + TEST_SESSION_ID))
                .thenReturn(chunkHashes);
        when(cacheUtils.hashGetAll("file:upload:chunk_keys:" + TEST_SESSION_ID))
                .thenReturn(new HashMap<>());

        // 执行获取，不应该抛出异常
        FileUploadState result = stateManager.getState(TEST_SESSION_ID);

        // 验证状态仍然返回，即使有解析错误
        assertThat(result).isNotNull();
        // 原始值应该被保留
        assertThat(result.getChunkHashes()).containsEntry("chunk_0", "invalid-json-{{}");
    }

    @Test
    void 并发访问_更新活动时间不会丢失状态() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class)))
                .thenReturn(testState)
                .thenReturn(null);  // 模拟第二次调用时状态丢失

        // 第一次调用成功
        stateManager.updateLastActivityTime(TEST_SESSION_ID);
        verify(cacheUtils, times(1)).saveToCache(anyString(), any(), anyLong());

        // 第二次调用时状态丢失，不应该保存
        stateManager.updateLastActivityTime(TEST_SESSION_ID);
        verify(cacheUtils, times(1)).saveToCache(anyString(), any(), anyLong());
    }

    @Test
    void 数据不一致_空集合处理() {
        // 模拟Redis返回null集合
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);
        when(cacheUtils.getIntegerSet(anyString())).thenReturn(new HashSet<>()); // 返回空集合而不是null
        when(cacheUtils.hashGetAll(anyString())).thenReturn(new HashMap<>()); // 返回空Map而不是null

        FileUploadState result = stateManager.getState(TEST_SESSION_ID);

        // 验证状态仍然返回，集合被清空
        assertThat(result).isNotNull();
        assertThat(result.getUploadedChunks()).isEmpty();
        assertThat(result.getProcessedChunks()).isEmpty();
        assertThat(result.getChunkHashes()).isEmpty();
    }

    @Test
    void 过期数据清理_确保所有相关数据被删除() {
        when(cacheUtils.takeFormCache(anyString(), eq(FileUploadState.class))).thenReturn(testState);

        // 执行删除
        stateManager.removeSession(TEST_SESSION_ID, TEST_SUID);

        // 验证所有相关的键都被删除，包括映射和集合成员
        verify(cacheUtils).hashDelete("file:upload:filename_suid_mapping", TEST_FILE_NAME + "_" + TEST_SUID);
        verify(cacheUtils).setRemove("file:upload:active_sessions", TEST_SESSION_ID);
        verify(cacheUtils).setRemove("file:upload:paused_sessions", TEST_SESSION_ID);

        // 验证所有存储键都被删除
        verify(cacheUtils).deleteCache("file:upload:session:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:uploaded_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:processed_chunks:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:chunk_hashes:" + TEST_SESSION_ID);
        verify(cacheUtils).deleteCache("file:upload:chunk_keys:" + TEST_SESSION_ID);
    }
}