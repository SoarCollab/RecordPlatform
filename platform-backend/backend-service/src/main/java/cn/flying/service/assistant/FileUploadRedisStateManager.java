package cn.flying.service.assistant;

import cn.flying.common.util.CacheUtils;
import cn.flying.dao.vo.file.FileUploadState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 文件上传状态Redis管理器
 * 负责FileUploadState的Redis存储、获取和更新
 */
@Component
@Slf4j
public class FileUploadRedisStateManager {

    // 键前缀和过期时间配置
    private static final String ACTIVE_UPLOADS_KEY = "file:upload:active_sessions";
    private static final String FILE_CLIENT_MAPPING_KEY = "file:upload:filename_client_mapping";
    private static final String PAUSED_SESSIONS_KEY = "file:upload:paused_sessions";
    private static final String SESSION_KEY_PREFIX = "file:upload:session:";
    private static final String SESSION_UPLOADED_CHUNKS_KEY_PREFIX = "file:upload:uploaded_chunks:";
    private static final String SESSION_PROCESSED_CHUNKS_KEY_PREFIX = "file:upload:processed_chunks:";
    private static final String SESSION_CHUNK_HASHES_KEY_PREFIX = "file:upload:chunk_hashes:";
    private static final String SESSION_KEYS_KEY_PREFIX = "file:upload:chunk_keys:";

    // 默认过期时间: 24小时
    private static final long DEFAULT_SESSION_EXPIRE_SECONDS = 24 * 60 * 60;

    @Resource
    private CacheUtils cacheUtils;

    /**
     * 保存新创建的上传状态
     */
    public void saveNewState(FileUploadState state) {
        String sessionId = state.getSessionId();
        String stateKey = getSessionKey(sessionId);

        // 1. 保存会话状态本身
        cacheUtils.saveToCache(stateKey, state, DEFAULT_SESSION_EXPIRE_SECONDS);

        // 2. 将会话ID添加到活跃会话集合
        cacheUtils.setAdd(ACTIVE_UPLOADS_KEY, sessionId);

        // 3. 保存文件名和客户端ID到会话ID的映射
        String fileClientKey = state.getFileName() + "_" + state.getClientId();
        cacheUtils.hashPut(FILE_CLIENT_MAPPING_KEY, fileClientKey, sessionId);

        // 4. 初始化上传分片和处理分片的集合
        String uploadedChunksKey = getUploadedChunksKey(sessionId);
        String processedChunksKey = getProcessedChunksKey(sessionId);

        // 5. 设置过期时间
        cacheUtils.setExpire(uploadedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
        cacheUtils.setExpire(processedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);

        log.info("Redis: 保存新的上传状态 sessionId={}, fileClientKey={}", sessionId, fileClientKey);
    }

    /**
     * 根据会话ID获取上传状态
     */
    public FileUploadState getState(String sessionId) {
        String stateKey = getSessionKey(sessionId);
        FileUploadState state = cacheUtils.takeFormCache(stateKey, FileUploadState.class);

        if (state != null) {
            // 更新分片集合等动态数据
            populateStateCollections(state);
        }

        return state;
    }

    /**
     * 更新会话状态
     */
    public void updateState(FileUploadState state) {
        String sessionId = state.getSessionId();
        String stateKey = getSessionKey(sessionId);

        // 保存状态基本信息
        cacheUtils.saveToCache(stateKey, state, DEFAULT_SESSION_EXPIRE_SECONDS);

        // 更新上传分片集合
        String uploadedChunksKey = getUploadedChunksKey(sessionId);
        cacheUtils.setAddIntegers(uploadedChunksKey, state.getUploadedChunks());
        cacheUtils.setExpire(uploadedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);

        // 更新处理分片集合
        String processedChunksKey = getProcessedChunksKey(sessionId);
        cacheUtils.setAddIntegers(processedChunksKey, state.getProcessedChunks());
        cacheUtils.setExpire(processedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);

        // 更新分片哈希
        String chunkHashesKey = getChunkHashesKey(sessionId);
        cacheUtils.hashPutAll(chunkHashesKey, state.getChunkHashes());
        cacheUtils.setExpire(chunkHashesKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);

        // 更新密钥
        String keysKey = getKeysKey(sessionId);
        Map<String, byte[]> keysMap = state.getKeys().entrySet().stream()
                .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        cacheUtils.hashPutAll(keysKey, keysMap);
        cacheUtils.setExpire(keysKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 添加上传的分片
     */
    public void addUploadedChunk(String sessionId, int chunkNumber) {
        String uploadedChunksKey = getUploadedChunksKey(sessionId);
        cacheUtils.setAdd(uploadedChunksKey, String.valueOf(chunkNumber));

        // 更新最后活动时间
        updateLastActivityTime(sessionId);
    }

    /**
     * 添加处理完成的分片
     */
    public void addProcessedChunk(String sessionId, int chunkNumber) {
        String processedChunksKey = getProcessedChunksKey(sessionId);
        cacheUtils.setAdd(processedChunksKey, String.valueOf(chunkNumber));

        // 更新最后活动时间
        updateLastActivityTime(sessionId);
    }

    /**
     * 添加分片哈希
     */
    public void addChunkHash(String sessionId, String chunkKey, String hash) {
        String chunkHashesKey = getChunkHashesKey(sessionId);
        cacheUtils.hashPut(chunkHashesKey, chunkKey, hash);
    }

    /**
     * 保存分片密钥
     */
    public void addChunkKey(String sessionId, int chunkNumber, byte[] keyData) {
        String keysKey = getKeysKey(sessionId);
        cacheUtils.hashPut(keysKey, String.valueOf(chunkNumber), keyData);
    }

    /**
     * 获取分片密钥
     */
    public Map<Integer, byte[]> getChunkKeys(String sessionId) {
        String keysKey = getKeysKey(sessionId);
        Map<Object, Object> allEntries = cacheUtils.hashGetAll(keysKey);

        return allEntries.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> Integer.parseInt((String) entry.getKey()),
                        entry -> cacheUtils.hashGet(keysKey, (String) entry.getKey(), byte[].class)
                ));
    }

    /**
     * 更新最后活动时间
     */
    public void updateLastActivityTime(String sessionId) {
        String stateKey = getSessionKey(sessionId);
        FileUploadState state = cacheUtils.takeFormCache(stateKey, FileUploadState.class);

        if (state != null) {
            state.updateLastActivity();
            cacheUtils.saveToCache(stateKey, state, DEFAULT_SESSION_EXPIRE_SECONDS);
        }
    }

    /**
     * 添加暂停的会话
     */
    public void addPausedSession(String sessionId) {
        cacheUtils.setAdd(PAUSED_SESSIONS_KEY, sessionId);
    }

    /**
     * 移除暂停的会话
     */
    public boolean removePausedSession(String sessionId) {
        boolean wasPaused = cacheUtils.setIsMember(PAUSED_SESSIONS_KEY, sessionId);
        cacheUtils.setRemove(PAUSED_SESSIONS_KEY, sessionId);
        return wasPaused;
    }

    /**
     * 检查会话是否暂停
     */
    public boolean isSessionPaused(String sessionId) {
        return cacheUtils.setIsMember(PAUSED_SESSIONS_KEY, sessionId);
    }

    /**
     * 根据文件名和客户端ID获取会话ID
     */
    public String getSessionIdByFileClientKey(String fileName, String clientId) {
        String fileClientKey = fileName + "_" + clientId;
        return cacheUtils.hashGet(FILE_CLIENT_MAPPING_KEY, fileClientKey, String.class);
    }

    /**
     * 移除会话及相关数据
     */
    public boolean removeSession(String sessionId) {
        FileUploadState state = getState(sessionId);
        if (state == null) {
            return false;
        }

        // 删除映射
        String fileClientKey = state.getFileName() + "_" + state.getClientId();
        cacheUtils.hashDelete(FILE_CLIENT_MAPPING_KEY, fileClientKey);

        // 删除分片集合
        cacheUtils.deleteCache(getUploadedChunksKey(sessionId));
        cacheUtils.deleteCache(getProcessedChunksKey(sessionId));

        // 删除哈希和密钥
        cacheUtils.deleteCache(getChunkHashesKey(sessionId));
        cacheUtils.deleteCache(getKeysKey(sessionId));

        // 删除状态
        cacheUtils.deleteCache(getSessionKey(sessionId));

        // 从活跃会话和暂停会话中移除
        cacheUtils.setRemove(ACTIVE_UPLOADS_KEY, sessionId);
        cacheUtils.setRemove(PAUSED_SESSIONS_KEY, sessionId);

        return true;
    }

    /**
     * 获取所有活跃会话ID
     */
    public Set<String> getAllActiveSessionIds() {
        Set<String> members = cacheUtils.setMembers(ACTIVE_UPLOADS_KEY);
        return members != null ? members : new HashSet<>();
    }

    // 私有辅助方法

    /**
     * 填充状态中的集合数据
     */
    private void populateStateCollections(FileUploadState state) {
        String sessionId = state.getSessionId();

        // 获取已上传分片
        String uploadedChunksKey = getUploadedChunksKey(sessionId);
        Set<Integer> uploadedChunks = cacheUtils.getIntegerSet(uploadedChunksKey);
        state.getUploadedChunks().clear();
        state.getUploadedChunks().addAll(uploadedChunks);

        // 获取已处理分片
        String processedChunksKey = getProcessedChunksKey(sessionId);
        Set<Integer> processedChunks = cacheUtils.getIntegerSet(processedChunksKey);
        state.getProcessedChunks().clear();
        state.getProcessedChunks().addAll(processedChunks);

        // 获取分片哈希
        String chunkHashesKey = getChunkHashesKey(sessionId);
        Map<Object, Object> rawChunkHashes = cacheUtils.hashGetAll(chunkHashesKey);

        // 检查返回值是否为空
        if (rawChunkHashes != null) {
            // 转换 Map<Object, Object>
            Map<String, String> chunkHashes = rawChunkHashes.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String && entry.getValue() instanceof String)
                    .collect(Collectors.toMap(
                            entry -> (String) entry.getKey(),
                            entry -> (String) entry.getValue()
                    ));

            // 更新状态中的分片哈希集合
            state.getChunkHashes().clear();
            state.getChunkHashes().putAll(chunkHashes);
        } else {
            // 如果 Redis 中没有数据，清空集合以确保一致性
            state.getChunkHashes().clear();
        }


        // 获取密钥
        Map<Integer, byte[]> keys = getChunkKeys(sessionId);
        state.getKeys().clear();
        state.getKeys().putAll(keys);
    }

    // 键构建助手方法

    private String getSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String getUploadedChunksKey(String sessionId) {
        return SESSION_UPLOADED_CHUNKS_KEY_PREFIX + sessionId;
    }

    private String getProcessedChunksKey(String sessionId) {
        return SESSION_PROCESSED_CHUNKS_KEY_PREFIX + sessionId;
    }

    private String getChunkHashesKey(String sessionId) {
        return SESSION_CHUNK_HASHES_KEY_PREFIX + sessionId;
    }

    private String getKeysKey(String sessionId) {
        return SESSION_KEYS_KEY_PREFIX + sessionId;
    }
}