package cn.flying.service.impl;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.UidEncoder;
import cn.flying.dao.vo.file.*;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.encryption.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @program: RecordPlatform
 * @description: 文件上传服务 (包含业务逻辑、状态管理、文件操作等)
 * @author flyingcoding
 * @create: 2025-03-31 11:22
 *
 * 注意：此类不使用类级别 @Transactional，因为：
 *  flyingcoding
 *  flyingcoding
 *  flyingcoding
 */
@Service
@Slf4j
public class FileUploadServiceImpl implements FileUploadService {

    // --- 目录常量 ---
    private static final String UPLOAD_BASE_DIR = "uploads"; // 原始分片存储基础目录
    private static final String PROCESSED_BASE_DIR = "processed"; // 加密后分片存储基础目录
    // --- 文件处理常量 ---
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB I/O 缓冲区大小
    private static final long MAX_FILE_SIZE_BYTES = 4096 * 1024 * 1024L; // 4GB 最大文件大小限制
    private static final int MAX_CHUNK_SIZE_BYTES = 80 * 1024 * 1024; // 80MB 最大分片大小 (Dubbo载荷限制100MB，预留安全边际)
    private static final int PROGRESS_UPDATE_INTERVAL_MS = 1000; // 进度日志更新间隔（毫秒）
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$");
    private static final String HASH_ALGORITHM = "SHA-256"; // 哈希算法
    private static final String HASH_SEPARATOR = "\n--HASH--\n"; // 哈希值前的分隔符
    private static final String KEY_SEPARATOR = "\n--NEXT_KEY--\n"; // 下一个密钥前的分隔符
    // --- 允许的文件类型 ---
    private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z"
    );
    private static final Map<String, String> ALLOWED_MIME_TYPES = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/gif", "gif"),
            Map.entry("application/pdf", "pdf"), Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("text/plain", "txt"), Map.entry("application/zip", "zip"),
            Map.entry("application/x-rar-compressed", "rar"), Map.entry("application/x-7z-compressed", "7z")
    );
    // --- 线程池配置 ---
    private final ExecutorService fileProcessingExecutor;
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
    @Resource
    private ApplicationEventPublisher eventPublisher;
    // Redis状态管理器
    @Resource
    private FileUploadRedisStateManager redisStateManager;

    // 加密策略工厂
    @Resource
    private EncryptionStrategyFactory encryptionStrategyFactory;

    @Resource
    private FileService fileService;

    public FileUploadServiceImpl() {
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxPoolSize = Runtime.getRuntime().availableProcessors() * 2;
        this.fileProcessingExecutor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(@NotNull Runnable r) {
                        Thread t = new Thread(r, "文件处理器-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：调用者线程执行
        );
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(Paths.get(UPLOAD_BASE_DIR));
            Files.createDirectories(Paths.get(PROCESSED_BASE_DIR));
            log.info("The basic upload and processing directory has been ensured to exist");
        } catch (IOException e) {
            log.error("初始化基础目录失败", e);
            // 初始化失败是严重问题，可以抛出运行时异常阻止应用启动
            throw new RuntimeException("创建基础目录失败", e);
        }
        // 启动定时清理任务
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredUploadSessions, 1, 1, TimeUnit.HOURS);
        log.info("The scheduled cleaning task has been initiated...");
    }

    /**
     * 定时任务清理过期的上传会话
     * 改为每6小时执行一次，提高清理效率
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 60 * 60 * 1000) // 每6小时执行一次（启动后1小时执行）
    public void cleanupExpiredUploadSessions() {
        long now = System.currentTimeMillis();
        long timeoutMillis = 12 * 60 * 60 * 1000L; // 12 小时
        long pausedTimeoutMillis = 24 * 60 * 60 * 1000L; // 暂停会话24小时超时

        log.info("-------------开始执行定时清理任务，查找超过 {} 小时未活动的上传会话----------------",
                TimeUnit.MILLISECONDS.toHours(timeoutMillis));

        Set<String> activeSessionIds = redisStateManager.getAllActiveSessionIds();
        List<String> expiredSessionIds = new ArrayList<>();
        List<String> pausedExpiredSessionIds = new ArrayList<>();

        for (String clientId : activeSessionIds) {
            FileUploadState state = redisStateManager.getState(clientId);
            if (state != null) {
                long inactiveDuration = now - state.getLastActivityTime();
                boolean isPaused = redisStateManager.isSessionPaused(clientId);

                // 暂停会话使用更长的超时时间
                long currentTimeoutMillis = isPaused ? pausedTimeoutMillis : timeoutMillis;

                if (inactiveDuration >= currentTimeoutMillis) {
                    if (isPaused) {
                        pausedExpiredSessionIds.add(clientId);
                        log.warn("发现过期暂停会话: 客户端ID={}, 文件名={}, 上次活动时间={}, 暂停时长={}小时",
                                clientId, state.getFileName(),
                                Instant.ofEpochMilli(state.getLastActivityTime()),
                                TimeUnit.MILLISECONDS.toHours(inactiveDuration));
                    } else {
                        expiredSessionIds.add(clientId);
                        log.warn("发现过期活跃会话: 客户端ID={}, 文件名={}, 上次活动时间={}, 未活动时长={}小时",
                                clientId, state.getFileName(),
                                Instant.ofEpochMilli(state.getLastActivityTime()),
                                TimeUnit.MILLISECONDS.toHours(inactiveDuration));
                    }
                }
            } else {
                // 处理状态丢失但仍在活跃集合中的会话
                expiredSessionIds.add(clientId);
                log.warn("发现状态丢失的会话ID: {}", clientId);
            }
        }

        // 使用通用的SUID，避免依赖MDC
        int cleanedCount = 0;
        for (String clientId : expiredSessionIds) {
            if (cleanupUploadSessionInternal("", clientId)) { // 传空SUID，让内部方法处理
                cleanedCount++;
            }
        }
        for (String clientId : pausedExpiredSessionIds) {
            if (cleanupUploadSessionInternal("", clientId)) {
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("定时清理完成，共清理 {} 个过期上传会话（活跃: {}, 暂停: {}）。",
                    cleanedCount, expiredSessionIds.size(), pausedExpiredSessionIds.size());
        } else {
            log.info("定时清理完成，未发现需要清理的过期上传会话。");
        }
    }

    // === Service 方法实现 ===

    /**
     * 内部方法清理指定 clientId 的上传状态和相关文件目录
     * 支持处理SUID为空的情况（用于定时清理任务）
     *
     * 修复竞态条件：先同步清理文件，再删除 Redis 状态
     * 这样即使系统崩溃，定时清理任务仍可通过 Redis 状态找到残留文件
     */
    private boolean cleanupUploadSessionInternal(String SUID, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state != null) {
            log.info("--------------------开始清理会话 {} 的相关文件-----------------", clientId);

            // 如果SUID为空，跳过文件系统清理，仅清理Redis状态
            final String actualSUID = (SUID == null || SUID.isEmpty()) ? "" : SUID;

            if (actualSUID.isEmpty()) {
                // 对于定时清理任务，我们无法获取准确的SUID，但仍然可以清理Redis状态
                log.warn("SUID为空，跳过文件系统清理，仅清理Redis状态: 客户端ID={}", clientId);
            } else {
                // 同步清理文件，确保文件删除后再删除 Redis 状态
                // 这样即使清理失败，定时任务仍可通过 Redis 状态重试
                try {
                    Path uploadDir = getUploadSessionDir(actualSUID, clientId);
                    cleanupDirectory(uploadDir);
                    Path processedDir = getProcessedSessionDir(actualSUID, clientId);
                    cleanupDirectory(processedDir);
                    log.info("会话 {} 文件清理完成。", clientId);
                } catch (Exception e) {
                    log.error("会话 {} 文件清理失败，将保留 Redis 状态以便重试: {}", clientId, e.getMessage());
                    // 文件清理失败时保留 Redis 状态，让定时任务重试
                    return false;
                }
            }

            // 文件清理成功后，从Redis中移除状态
            redisStateManager.removeSession(state.getClientId(), actualSUID);

            return true; // 状态已找到并清理完成
        } else {
            // 尝试从暂停集合中移除，以防状态已丢失但仍在暂停集合中
            boolean removedFromPaused = redisStateManager.removePausedSession(clientId);
            if (removedFromPaused) {
                log.warn("清理会话 {} 时，状态已不存在，但从暂停集合移除成功。", clientId);
            } else {
                log.debug("尝试清理会话 {}，但状态已不存在。", clientId);
            }
            return false; // 会话状态未找到
        }
    }

    // === 路径构建辅助方法 ===
    private Path getUploadSessionDir(String SUID, String clientId) {
        return Paths.get(UPLOAD_BASE_DIR, SUID, clientId).toAbsolutePath().normalize();
    }

    /**
     * 递归删除目录及其内容
     */
    private void cleanupDirectory(Path dirPath) {
        if (Files.exists(dirPath)) {
            try (Stream<Path> walk = Files.walk(dirPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(this::tryDelete);
                log.info("已清理目录: {}", dirPath);
            } catch (IOException e) {
                log.error("清理目录时发生错误: {}", dirPath, e);
            }
        } else {
            log.debug("尝试清理目录，但目录不存在: {}", dirPath);
        }
    }

    private Path getProcessedSessionDir(String SUID, String clientId) {
        return Paths.get(PROCESSED_BASE_DIR, SUID, clientId).toAbsolutePath().normalize();
    }

    /**
     * 尝试删除路径，记录错误但不抛出
     */
    private void tryDelete(Path path) {
        try {
            Files.delete(path);
        } catch (NoSuchFileException e) {
            // Ignore
        } catch (DirectoryNotEmptyException e) {
            log.warn("无法删除非空目录 (可能存在并发访问或延迟): {}", path);
        } catch (IOException e) {
            log.error("删除路径失败: {}", path, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("开始关闭文件上传服务...");
        // 关闭定时任务
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 优雅关闭文件处理线程池
        fileProcessingExecutor.shutdown();
        try {
            if (!fileProcessingExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                fileProcessingExecutor.shutdownNow();
                log.warn("文件处理线程池被强制关闭。");
            } else {
                log.info("文件处理线程池已优雅关闭。");
            }
        } catch (InterruptedException e) {
            fileProcessingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("等待线程池关闭时被中断。", e);
        }
        log.info("文件上传服务关闭完成。");
    }

    /**
     * 处理开始上传请求
     *
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     */
    @Override
    public StartUploadVO startUpload(Long userId, String fileName, long fileSize, String contentType, String clientId, int chunkSize, int totalChunks) {

        //获取加密后的uid，防止数据泄漏
        String uidStr = String.valueOf(userId);
        String SUID = UidEncoder.encodeUid(uidStr);

        // 如果未提供，则生成一个新的客户端ID（随机生成，作为客户端凭证）
        if (CommonUtils.isBlank(clientId)) {
            clientId = UidEncoder.encodeCid(SUID);
        }
        String fileClientKey = fileName + "_" + SUID;

        log.info("处理上传开始请求: 文件名={}, 文件大小={}, 内容类型={}, 用户SUID={}, 客户端ID={}",
                fileName, fileSize, contentType, SUID, clientId);

        // --- 输入验证 ---
        if (!isValidFileName(fileName)) {
            throw new GeneralException("文件名包含非法字符");
        }
        if (fileSize <= 0) {
            throw new GeneralException("文件大小必须大于0");
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException("文件大小超过限制 (" + (MAX_FILE_SIZE_BYTES / 1024 / 1024 / 1024) + "GB)");
        }
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE_BYTES) {
            throw new GeneralException("分片大小必须在 1B 到 " + (MAX_CHUNK_SIZE_BYTES / 1024 / 1024) + "MB 之间");
        }
        if (!isFileTypeAllowed(fileName, contentType)) {
            throw new GeneralException("不支持的文件类型");
        }

        // --- 检查是否可恢复 ---
        String existClientId = redisStateManager.getSessionIdByFileClientKey(fileName, SUID);
        if (existClientId != null) {
            FileUploadState existingState = redisStateManager.getState(existClientId);
            if (existingState != null && existingState.getFileSize() == fileSize) {
                log.info("发现可恢复的上传会话: 客户端ID={}, 文件客户端键={}", existClientId, fileClientKey);
                redisStateManager.removePausedSession(existClientId); // 恢复会话（如果之前暂停了）
                redisStateManager.updateLastActivityTime(existClientId);
                // 返回恢复成功的 DTO
                return createResumeDto(existingState);
            } else {
                log.warn("发现旧会话但无法恢复 (状态丢失或文件大小不匹配): 旧客户端ID={}, 文件客户端键={}", existClientId, fileClientKey);
                if (existingState != null) {
                    cleanupUploadSessionInternal(SUID, existClientId); // 主动清理旧状态
                }
            }
        }

        // --- 创建新会话 ---
        try {
            FileUploadState newState = new FileUploadState(userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks);
            redisStateManager.saveNewState(newState, SUID);

            // 确保客户端和会话的目录存在
            Files.createDirectories(getUploadSessionDir(SUID, clientId));
            Files.createDirectories(getProcessedSessionDir(SUID, clientId));

            log.info("创建新的上传会话: 客户端ID={}, 文件客户端键={}", SUID, fileClientKey);
            // 返回创建成功的 DTO
            return createNewSessionDto(newState);

        } catch (IOException e) {
            log.error("创建上传会话或目录失败: 文件客户端键={}", fileClientKey, e);
            // 包装成自定义异常，方便 Controller 统一处理
            throw new GeneralException("创建上传会话失败: " + e.getMessage());
        }
    }

    /**
     * 处理分片上传
     *
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传已暂停
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     * @throws GeneralException 安全相关操作失败 (如哈希)
     */
    public void uploadChunk(Long userId, String clientId, int chunkNumber, MultipartFile file) {
        //获取加密后的uid，防止数据泄漏
        String SUID = UidEncoder.encodeUid(String.valueOf(userId));

        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或已过期: 客户端ID=" + clientId);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        if (redisStateManager.isSessionPaused(clientId)) {
            throw new GeneralException("上传已暂停，请先恢复上传: 客户端ID=" + clientId);
        }

        redisStateManager.updateLastActivityTime(clientId); // 更新活动时间

        if (!isValidChunkNumber(chunkNumber, state.getTotalChunks())) {
            throw new GeneralException("无效的分片序号: 序号=" + chunkNumber + ", 总数=" + state.getTotalChunks());
        }
        if (file.isEmpty()) {
            throw new GeneralException("上传的分片不能为空: 客户端ID=" + clientId + ", 序号=" + chunkNumber);
        }

        // --- 优化：边保存边计算哈希 ---
        Path chunkPath = getChunkUploadPath(SUID, clientId, chunkNumber);
        String calculatedHashBase64;

        try {
            // 检查是否已处理
            if (state.getProcessedChunks().contains(chunkNumber)) {
                log.info("分片 {} 已处理过，跳过: 客户端ID={}", chunkNumber, clientId);
                // 注意：这里不抛异常，因为可能客户端重传了已处理的分片
                return; // 直接返回，不报错
            }

            log.debug("开始保存分片: 路径={}", chunkPath);
            Files.createDirectories(chunkPath.getParent()); // 确保目录存在

            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            long bytesWritten;
            try (InputStream inputStream = file.getInputStream();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
                 OutputStream outputStream = Files.newOutputStream(chunkPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                bytesWritten = digestInputStream.transferTo(outputStream);
            }

            if (bytesWritten != file.getSize()) {
                log.warn("写入字节数 ({}) 与文件大小 ({}) 不符: 分片={}, 客户端ID={}",
                        bytesWritten, file.getSize(), chunkNumber, clientId);
                // 可以考虑是否需要抛异常
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR);
            }

            byte[] hashBytes = digest.digest();
            calculatedHashBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);

            log.info("分片 {} 保存成功: 客户端ID={}, 大小={}, 哈希={}", chunkNumber, clientId, bytesWritten, calculatedHashBase64);

            // 使用原子操作同时更新已上传分片集合和分片哈希，确保高并发下的数据一致性
            if (!redisStateManager.addUploadedChunkWithHash(clientId, chunkNumber, calculatedHashBase64)) {
                log.error("Redis 原子更新失败: 客户端ID={}, 分片={}", clientId, chunkNumber);
                tryDelete(chunkPath);
                throw new GeneralException("更新上传状态失败");
            }

            // --- 触发异步处理 ---
            processChunkImmediately(SUID, state, chunkNumber, chunkPath, calculatedHashBase64);

            updateUploadProgress(state, "上传分片 " + chunkNumber);

        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法 {} 不可用!", HASH_ALGORITHM, e);
            tryDelete(chunkPath);
            throw new GeneralException("内部服务器错误：哈希算法不可用");
        } catch (IOException e) {
            log.error("保存或哈希分片 {} 失败: 客户端ID={}", chunkNumber, state.getClientId(), e);
            tryDelete(chunkPath);
            throw new GeneralException("保存分片失败: " + e.getMessage());
        } catch (Exception e) { // 捕获其他潜在异常
            log.error("处理分片 {} 时发生未知错误: 客户端ID={}", chunkNumber, state.getClientId(), e);
            tryDelete(chunkPath);

            //根据异常类型决定是否清理Redis状态
            if (isCriticalUploadError(e)) {
                try {
                    redisStateManager.removeSession(clientId, SUID);
                    log.info("严重异常，已清理Redis状态: 客户端ID={}, 异常类型={}", clientId, e.getClass().getSimpleName());
                } catch (Exception cleanupEx) {
                    log.warn("清理Redis状态时发生异常: 客户端ID={}", clientId, cleanupEx);
                }
            }
            throw new RuntimeException("处理分片时发生未知错误: " + e.getMessage(), e);
        }
    }


    // === 私有辅助方法 ===

    /**
     * 获取上传进度
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public ProgressVO getUploadProgress(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 客户端ID=" + clientId);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        redisStateManager.updateLastActivityTime(clientId);
        ProgressInfo progressInfo = calculateProgressInfo(state);

        ProgressVO responseDto = new ProgressVO(progressInfo.totalProgress,
                progressInfo.uploadProgressPercent, progressInfo.processProgressPercent,
                progressInfo.uploadedCount, progressInfo.processedCount, progressInfo.totalChunks,
                clientId
        );

        log.debug("获取进度成功: 客户端ID={}, 总进度={}%", clientId, progressInfo.totalProgress);
        return responseDto;
    }

    /**
     * 取消上传并清理资源
     *
     * @return 如果找到并清理了会话则返回 true，否则返回 false
     */
    public boolean cancelUpload(Long userId, String clientId) {
        String SUID = UidEncoder.encodeUid(String.valueOf(userId));
        log.info("收到取消上传请求: 客户端ID={}", clientId);
        return cleanupUploadSessionInternal(SUID, clientId); // 内部方法处理查找和清理
    }

    /**
     * 完成文件上传处理
     *
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传未完成（分片未处理完）
     * @throws GeneralException IO 操作失败
     */
    public void completeUpload(Long userId, String clientId) {

        String uidStr = String.valueOf(userId);
        String SUID = UidEncoder.encodeUid(uidStr);

        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或已过期: 客户端ID=" + clientId);
        }

        log.info("处理完成上传请求: 客户端ID={}, 文件名={}", clientId, state.getFileName());
        redisStateManager.updateLastActivityTime(clientId);

        // 检查是否所有分片都已处理
        int expectedChunks = state.getTotalChunks();
        boolean allProcessed = state.getProcessedChunks().size() == expectedChunks;

        if (!allProcessed) {
            log.warn("请求完成上传时，并非所有分片都已处理: 客户端ID={}, 已处理={}, 总数={}",
                    clientId, state.getProcessedChunks().size(), expectedChunks);

            // 使用非阻塞方式等待异步处理完成
            allProcessed = waitForChunkProcessingCompletionNonBlocking(clientId, expectedChunks);
            if (!allProcessed) {
                log.error("等待超时，仍有分片未处理完成: 客户端ID={}, 已处理={}, 总数={}",
                        clientId, state.getProcessedChunks().size(), expectedChunks);
                throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("processed", state.getProcessedChunks().size(), "expected", expectedChunks));
            }
        }

        try {
            // --- 执行最终步骤 (追加下一个分片的密钥) ---
            completeFileProcessing(SUID, state);

            // --- 清理原始上传分片 ---
            Path uploadSessionDir = getUploadSessionDir(SUID, clientId);
            cleanupDirectory(uploadSessionDir);
            log.info("原始上传分片目录已清理: {}", uploadSessionDir);

            // 调用文件服务初始化文件元信息
            fileService.prepareStoreFile(userId, state.getFileName());

            log.info("文件上传和处理流程完成: 客户端ID={}, 文件名={}", clientId, state.getFileName());

            // 收集处理后的文件和哈希值
            List<File> processedFiles = collectProcessedFiles(SUID, clientId);
            if (processedFiles == null) {
                log.error("收集处理后的文件失败，无法继续存证流程: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                // 更新文件状态为失败
                fileService.changeFileStatusByName(userId, state.getFileName(), FileUploadStatus.FAIL.getCode());
                // 清理Redis状态
                redisStateManager.removeSession(state.getClientId(), SUID);
                throw new GeneralException("收集处理后的文件失败，文件存证中止");
            }

            List<String> fileHashes = collectFileHashes(state);
            if (fileHashes == null) {
                log.error("收集文件哈希值失败，无法继续存证流程: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                // 更新文件状态为失败
                fileService.changeFileStatusByName(userId, state.getFileName(), FileUploadStatus.FAIL.getCode());
                // 清理Redis状态
                redisStateManager.removeSession(state.getClientId(), SUID);
                throw new GeneralException("收集文件哈希值失败，文件存证中止");
            }

            // 验证文件数量与哈希数量一致
            if (processedFiles.size() != fileHashes.size()) {
                log.error("处理后的文件数量({})与哈希值数量({})不匹配: 客户端ID={}, 文件名={}",
                        processedFiles.size(), fileHashes.size(), clientId, state.getFileName());
                // 更新文件状态为失败
                fileService.changeFileStatusByName(userId, state.getFileName(), FileUploadStatus.FAIL.getCode());
                // 清理Redis状态
                redisStateManager.removeSession(state.getClientId(), SUID);
                throw new GeneralException("文件数量与哈希数量不匹配，文件存证中止");
            }

            log.info("成功收集文件和哈希值: 客户端ID={}, 文件名={}, 分片数量={}",
                    clientId, state.getFileName(), processedFiles.size());

            // 发布文件存证事件，触发异步存证处理
            if (CommonUtils.isNotEmpty(eventPublisher)) {
                eventPublisher.publishEvent(new FileStorageEvent(
                        this,
                        userId,
                        state.getFileName(),
                        SUID,
                        state.getClientId(),
                        processedFiles,
                        fileHashes,
                        generateFileParam(state) // 生成文件参数
                ));

                log.info("已发布文件存证事件: 用户={}, 文件名={}, 分片数量={}", userId, state.getFileName(), processedFiles.size());

                // 立即清理Redis上传状态
                try {
                    redisStateManager.removeSession(state.getClientId(), SUID);
                    log.info("上传完成，已清理Redis状态: 客户端ID={}", clientId);
                } catch (Exception e) {
                    log.warn("清理Redis状态失败，但不影响主流程: 客户端ID={}", clientId, e);
                }
            } else {
                log.error("事件发布器未初始化，无法发送文件存证事件: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                // 更新文件状态为失败
                fileService.changeFileStatusByName(userId, state.getFileName(), FileUploadStatus.FAIL.getCode());
                // 事件发布器未初始化时也清理Redis状态
                redisStateManager.removeSession(state.getClientId(), SUID);
                throw new GeneralException("事件发布器未初始化，文件存证中止");
            }


        } catch (IOException e) {
            log.error("完成文件处理或清理时失败: 客户端ID={}", clientId, e);
            // 确保在异常情况下也清理Redis状态
            try {
                redisStateManager.removeSession(clientId, SUID);
                log.info("异常情况下已清理Redis状态: 客户端ID={}", clientId);
            } catch (Exception cleanupEx) {
                log.warn("清理Redis状态时发生异常: 客户端ID={}", clientId, cleanupEx);
            }
            throw new GeneralException("完成处理失败：" + e.getMessage());
        } catch (GeneralException e) {
            // GeneralException已经包含了状态清理逻辑，直接重新抛出
            throw e;
        } catch (Exception e) {
            log.error("完成文件处理时发生未知错误: 客户端ID={}", clientId, e);
            // 确保在异常情况下也清理Redis状态
            try {
                redisStateManager.removeSession(clientId, SUID);
                log.info("异常情况下已清理Redis状态: 客户端ID={}", clientId);
            } catch (Exception cleanupEx) {
                log.warn("清理Redis状态时发生异常: 客户端ID={}", clientId, cleanupEx);
            }
            throw new RuntimeException("完成处理时发生未知错误：" + e.getMessage(), e);
        }
    }

    /**
     * 暂停上传
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public void pauseUpload(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 客户端ID=" + clientId);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        redisStateManager.addPausedSession(clientId);
        redisStateManager.updateLastActivityTime(clientId);
        log.info("上传会话已暂停: 客户端ID={}", clientId);
    }

    /**
     * 恢复上传
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public ResumeUploadVO resumeUpload(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 客户端ID=" + clientId);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        boolean wasPaused = redisStateManager.removePausedSession(clientId);
        redisStateManager.updateLastActivityTime(clientId);

        // 创建包含已处理分片列表的恢复响应 DTO
        ResumeUploadVO responseDto = new ResumeUploadVO(
                new ArrayList<>(state.getProcessedChunks()), // 返回已处理的分片列表给客户端
                state.getTotalChunks()
        );

        log.info("上传会话已恢复 (之前是否暂停={}): 客户端ID={}", wasPaused, clientId);
        return responseDto;
    }

    /**
     * 检查文件上传状态
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public FileUploadStatusVO checkFileStatus(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或会话已被清除: 客户端ID=" + clientId);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        redisStateManager.updateLastActivityTime(clientId);
        boolean isPaused = redisStateManager.isSessionPaused(clientId);
        ProgressInfo progressInfo = calculateProgressInfo(state);
        String statusCode;

        if (isPaused) {
            statusCode = "PAUSED";
        } else if (progressInfo.processedCount == progressInfo.totalChunks && progressInfo.totalChunks > 0) {
            statusCode = "PROCESSING_COMPLETE";
        } else {
            statusCode = "UPLOADING";
        }

        FileUploadStatusVO responseDto = new FileUploadStatusVO(
                state.getFileName(), state.getFileSize(), state.getClientId(),
                isPaused, statusCode, progressInfo.totalProgress,
                new ArrayList<>(state.getProcessedChunks()),
                progressInfo.processedCount, progressInfo.totalChunks
        );

        log.debug("检查状态成功: 客户端ID={}, 状态={}, 进度={}%", clientId, statusCode, progressInfo.totalProgress);
        return responseDto;
    }

    /**
     * 验证上传会话所有权
     * 确保 Redis 中记录的 userId 与当前请求用户一致，否则拒绝访问
     */
    private void validateUploadOwnership(Long userId, FileUploadState state, String clientId) {
        Long stateUserId = state.getUserId();
        if (stateUserId == null || !stateUserId.equals(userId)) {
            log.warn("用户无权访问上传会话: userId={}, clientId={}, ownerUserId={}", userId, clientId, stateUserId);
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此上传会话");
        }
    }

    private ProgressInfo calculateProgressInfo(FileUploadState state) {
        int totalChunks = state.getTotalChunks();
        if (totalChunks == 0) {
            return new ProgressInfo(0, 0, 0, 100, 100, 100);
        }
        int uploadedCount = state.getUploadedChunks().size();
        int processedCount = state.getProcessedChunks().size();
        int uploadProgressPercent = (int) Math.round((uploadedCount * 100.0) / totalChunks);
        int processProgressPercent = (int) Math.round((processedCount * 100.0) / totalChunks);
        int totalProgress = (int) Math.round(uploadProgressPercent * 0.3 + processProgressPercent * 0.7);
        totalProgress = Math.max(0, Math.min(100, totalProgress));

        return new ProgressInfo(totalChunks, uploadedCount, processedCount,
                uploadProgressPercent, processProgressPercent, totalProgress);
    }

    // === 验证辅助方法 ===
    private boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        if (fileName.contains("/") || fileName.contains("\\")) return false;
        if (containsPotentiallyUnsafeCharacters(fileName)) return false;
        return SAFE_FILENAME_PATTERN.matcher(fileName).matches();
    }

    private boolean isFileTypeAllowed(String fileName, String contentType) {
        String extension = getFileExtension(fileName);
        if (contentType != null) {
            String lowerContentType = contentType.toLowerCase();
            if (ALLOWED_MIME_TYPES.containsKey(lowerContentType)) {
                return true;
            }
        }
        return extension != null && ALLOWED_FILE_EXTENSIONS.contains(extension);
    }

    // 创建 /upload/start 的恢复会话响应 DTO
    private StartUploadVO createResumeDto(FileUploadState state) {
        // 注意：恢复时返回的是已 *处理* (processed) 的分片列表，因为客户端需要知道哪些不需要再上传和处理
        return new StartUploadVO(state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                new ArrayList<>(state.getProcessedChunks()), // 返回已处理的分片序号列表
                true // 标记为恢复
        );
    }

    // 创建 /upload/start 的新会话响应 DTO
    private StartUploadVO createNewSessionDto(FileUploadState state) {
        return new StartUploadVO(
                state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                Collections.emptyList(), // 新会话没有已处理的分片
                false // 标记为非恢复
        );
    }

    private boolean containsPotentiallyUnsafeCharacters(String fileName) {
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (Character.isISOControl(c) || c == '\u202E' || c == '\u202B' || c == '\u202F') return true;
        }
        if (fileName.endsWith(".") || fileName.endsWith(" ")) return true;
        String upperName = fileName.toUpperCase();
        return upperName.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$");
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return null;
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0 && dotIndex < fileName.length() - 1) ? fileName.substring(dotIndex + 1).toLowerCase() : null;
    }

    /**
     * 异步处理已上传的分片：对其进行加密，并在末尾附加其原始哈希值。
     * <p>使用可配置的加密策略（AES-GCM 或 ChaCha20-Poly1305）</p>
     *
     * <h3>加密分片文件结构（v1）：</h3>
     * <pre>
     * [版本头: 4B] [IV/Nonce: 12B] [加密数据] [认证标签] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
     * </pre>
     */
    private void processChunkImmediately(String SUID, FileUploadState state, int chunkNumber, Path chunkPath, String chunkHashBase64) {
        CompletableFuture.runAsync(() -> {
            String clientId = state.getClientId();
            Path processedChunkPath = getChunkProcessedPath(SUID, clientId, chunkNumber);
            log.debug("-------------开始异步处理分片 {}: 原始路径={}, 加密算法={} -------------",
                    chunkNumber, chunkPath, encryptionStrategyFactory.getCurrentAlgorithmName());

            try {
                // 获取当前加密策略
                ChunkEncryptionStrategy strategy = encryptionStrategyFactory.getStrategy();

                // 1. 生成密钥和 IV
                SecretKey chunkSecretKey = strategy.generateKey();
                byte[] iv = strategy.generateIv();
                // 将密钥存储到Redis中
                redisStateManager.addChunkKey(clientId, chunkNumber, chunkSecretKey.getEncoded());

                // 2. 创建加密上下文（流式加密）
                EncryptionContext encryptionContext = strategy.createEncryptionContext(chunkSecretKey, iv);

                // 3. 加密并写入
                Files.createDirectories(processedChunkPath.getParent());
                try (InputStream fis = Files.newInputStream(chunkPath);
                     OutputStream fos = Files.newOutputStream(processedChunkPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                    // 写入版本头（标识加密算法，支持后向兼容）
                    byte[] header = ChunkFileHeader.createHeader(strategy);
                    fos.write(header);

                    fos.write(iv); // 写入 IV/Nonce
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] encryptedBytes = strategy.encryptUpdate(encryptionContext, buffer, 0, bytesRead);
                        if (encryptedBytes.length > 0) fos.write(encryptedBytes);
                    }
                    byte[] finalBytes = strategy.encryptFinal(encryptionContext);
                    if (finalBytes.length > 0) fos.write(finalBytes);

                    // 追加哈希
                    fos.write(HASH_SEPARATOR.getBytes(StandardCharsets.UTF_8));
                    fos.write(chunkHashBase64.getBytes(StandardCharsets.UTF_8));
                }

                // 4. 标记处理完成
                redisStateManager.addProcessedChunk(clientId, chunkNumber);
                FileUploadState updatedState = redisStateManager.getState(clientId);
                if (updatedState != null) {
                    updateUploadProgress(updatedState, "处理完分片 " + chunkNumber);
                }
                log.info("分片 {} 处理成功: 客户端ID={}, 处理后路径={}, 算法={}",
                        chunkNumber, clientId, processedChunkPath, strategy.getAlgorithmName());

            } catch (EncryptionException e) {
                log.error("分片 {} 加密失败: 客户端ID={}, 算法={}", chunkNumber, clientId,
                        encryptionStrategyFactory.getCurrentAlgorithmName(), e);
                tryDelete(processedChunkPath);
            } catch (Exception e) {
                log.error("异步处理分片 {} 失败: 客户端ID={}", chunkNumber, clientId, e);
                tryDelete(processedChunkPath);
            }
        }, fileProcessingExecutor);
    }

    /**
     * 文件处理的最后一步：追加下一个分片的密钥。
     */
    private void completeFileProcessing(String SUID, FileUploadState state) throws IOException {
        log.info("------------开始最终处理步骤 (追加下一个分片密钥): 客户端ID={}--------------", state.getClientId());
        int totalChunks = state.getTotalChunks();
        Map<Integer, byte[]> keys = redisStateManager.getChunkKeys(state.getClientId());

        // NPE 防护：检查 keys 是否为 null
        if (keys == null) {
            log.error("获取分片密钥失败: keys 为 null, 客户端ID={}. 无法完成处理。", state.getClientId());
            throw new IOException("无法完成处理：无法获取分片密钥信息。");
        }

        if (keys.size() < totalChunks) {
            log.error("密钥数量 ({}) 少于分片总数 ({}), 客户端ID={}. 无法完成处理。", keys.size(), totalChunks, state.getClientId());
            throw new IOException("无法完成处理：并非所有分片的密钥都可用。");
        }
        for (int i = 0; i < totalChunks; i++) {
            if (keys.get(i) == null) {
                log.error("分片 {} 的密钥丢失 (为 null), 客户端ID={}. 无法完成处理。", i, state.getClientId());
                throw new IOException("无法完成处理：分片 " + i + " 的密钥丢失。");
            }
        }

        for (int i = 0; i < totalChunks - 1; i++) {
            Path currentChunkPath = getChunkProcessedPath(SUID, state.getClientId(), i);
            byte[] nextChunkKey = keys.get(i + 1);
            appendKeyToFile(currentChunkPath, nextChunkKey, i);
        }

        // 为最后一个分片追加第一个分片的密钥（形成环形链）
        if (totalChunks > 0) {
            int lastChunkIndex = totalChunks - 1;
            Path lastChunkPath = getChunkProcessedPath(SUID, state.getClientId(), lastChunkIndex);
            byte[] firstChunkKey = keys.get(0);
            appendKeyToFile(lastChunkPath, firstChunkKey, lastChunkIndex);
        }

        log.info("最终处理步骤 (追加下一个分片密钥) 完成: 客户端ID={}", state.getClientId());
    }

    /**
     * 辅助方法：追加密钥到文件
     */
    private void appendKeyToFile(Path filePath, byte[] keyBytes, int chunkIndex) throws IOException {
        if (Files.exists(filePath)) {
            try {
                String keyBase64 = Base64.getEncoder().encodeToString(keyBytes);
                byte[] dataToAppend = (KEY_SEPARATOR + keyBase64).getBytes(StandardCharsets.UTF_8);
                try (OutputStream fos = Files.newOutputStream(filePath, StandardOpenOption.APPEND)) {
                    fos.write(dataToAppend);
                }
                log.debug("已将下一个密钥追加到分片 {}", chunkIndex);
            } catch (IOException e) {
                log.error("追加密钥到分片 {} 文件失败: 路径={}", chunkIndex, filePath, e);
                throw new IOException("追加密钥到分片 " + chunkIndex + " 失败", e);
            }
        } else {
            log.error("处理后的分片文件未找到，无法追加密钥: {}", filePath);
            throw new FileNotFoundException("处理后的分片文件未找到: " + filePath.getFileName());
        }
    }

    /**
     * 非阻塞方式等待分片处理完成
     * 使用 CompletableFuture 和 ScheduledExecutorService
     * <p>
     *  flyingcoding
     * - 基础等待时间: 60秒
     * - 每个分片增加: 3秒 (考虑加密处理时间)
     * - 最大等待时间: 1800秒 (30分钟，支持大文件)
     *
     * @param clientId       客户端ID
     * @param expectedChunks 期望的分片总数
     * @return 是否所有分片都已处理完成
     */
    private boolean waitForChunkProcessingCompletionNonBlocking(String clientId, int expectedChunks) {
        // P0-4 修复：优化超时计算
        // 基础60秒 + 每分片3秒，最大30分钟
        final int maxWaitTimeSeconds = Math.min(1800, 60 + expectedChunks * 3);
        final int checkIntervalMs = 500; // 检查间隔：500毫秒

        log.info("--------------开始非阻塞等待分片处理完成: 客户端ID={}, 期望分片数={}, 最大等待时间={}秒--------------",
                clientId, expectedChunks, maxWaitTimeSeconds);

        // 创建一个 CompletableFuture 来处理异步等待
        CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

        // 使用 ScheduledExecutorService 进行定期检查，避免忙等待
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChunkProcessingWaiter-" + clientId);
            t.setDaemon(true);
            return t;
        })) {

            // 进度跟踪
            final AtomicInteger checkCount = new AtomicInteger(0);
            final AtomicInteger lastProcessedCount = new AtomicInteger(0);
            final AtomicInteger stagnationCounter = new AtomicInteger(0);
            final long startTime = System.currentTimeMillis();

            // 定期检查任务
            ScheduledFuture<?> checkTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    int currentCheckCount = checkCount.incrementAndGet();
                    long elapsedTime = System.currentTimeMillis() - startTime;

                    // 检查超时
                    if (elapsedTime > maxWaitTimeSeconds * 1000L) {
                        log.warn("等待超时: 客户端ID={}, 耗时={}ms", clientId, elapsedTime);
                        completionFuture.complete(false);
                        return;
                    }

                    // 获取最新状态
                    FileUploadState currentState = redisStateManager.getState(clientId);
                    if (currentState == null) {
                        log.warn("等待过程中状态丢失: 客户端ID={}", clientId);
                        completionFuture.complete(false);
                        return;
                    }

                    int processedCount = currentState.getProcessedChunks().size();

                    // 检查是否所有分片都已处理完成
                    if (processedCount >= expectedChunks) {
                        log.info("所有分片处理完成: 客户端ID={}, 耗时={}ms, 检查次数={}",
                                clientId, elapsedTime, currentCheckCount);
                        completionFuture.complete(true);
                        return;
                    }

                    // 检测进度停滞
                    int lastCount = lastProcessedCount.get();
                    if (processedCount == lastCount) {
                        int stagnation = stagnationCounter.incrementAndGet();
                        if (stagnation >= 10) { // 5秒无进度（10次 * 500ms）
                            log.warn("检测到进度停滞: 客户端ID={}, 已处理={}/{}, 停滞时间={}秒",
                                    clientId, processedCount, expectedChunks, stagnation * checkIntervalMs / 1000);

                            if (stagnation >= 30) { // 15秒无进度，认为异常
                                log.error("进度长时间停滞，可能存在处理异常: 客户端ID={}", clientId);
                                completionFuture.complete(false);
                            }
                        }
                    } else {
                        // 有进度，重置停滞计数器
                        stagnationCounter.set(0);
                        lastProcessedCount.set(processedCount);
                    }

                } catch (Exception e) {
                    log.error("检查分片处理状态时发生异常: 客户端ID={}", clientId, e);
                    completionFuture.completeExceptionally(e);
                }
            }, 0, checkIntervalMs, TimeUnit.MILLISECONDS);

            try {
                // 等待完成或超时
                return completionFuture.get(maxWaitTimeSeconds + 10, TimeUnit.SECONDS);

            } catch (TimeoutException e) {
                log.error("CompletableFuture 等待超时: 客户端ID={}", clientId, e);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("等待被中断: 客户端ID={}", clientId, e);
                return false;
            } catch (ExecutionException e) {
                log.error("等待执行异常: 客户端ID={}", clientId, e.getCause());
                return false;
            } finally {
                // 取消定期检查任务
                // 注意：scheduler 的关闭由 try-with-resources 自动处理（Java 19+ ExecutorService 实现了 AutoCloseable）
                checkTask.cancel(true);
            }
        }  // try-with-resources 自动调用 scheduler.close()，等效于 shutdown() + awaitTermination()
    }

    /**
     * 收集处理后的文件（按分片索引顺序）
     */
    private List<File> collectProcessedFiles(String SUID, String clientId) {
        // 从Redis获取状态以确定总分片数
        FileUploadState state = redisStateManager.getState(clientId);
        int totalChunks = 0;

        if (state != null) {
            totalChunks = state.getTotalChunks();
            log.info("------------从Redis状态获取分片数: 客户端ID={}, 总分片数={}-------------", clientId, totalChunks);
        } else {
            log.warn("无法从Redis获取上传状态: 客户端ID={}", clientId);
        }

        List<File> orderedFiles = new ArrayList<>(totalChunks);

        log.info("------------开始按顺序收集处理后的文件: 客户端ID={}, 总分片数={}-------------", clientId, totalChunks);

        // 按分片索引顺序收集文件
        for (int i = 0; i < totalChunks; i++) {
            Path chunkPath = getChunkProcessedPath(SUID, clientId, i);

            if (Files.exists(chunkPath) && Files.isRegularFile(chunkPath)) {
                File chunkFile = chunkPath.toFile();
                orderedFiles.add(chunkFile);
            } else {
                log.error("分片文件不存在或不是常规文件: 索引={}, 路径={}", i, chunkPath);
                return null;
            }
        }

        // 验证收集的文件数量
        if (orderedFiles.size() != totalChunks) {
            log.error("收集的文件数量({})与预期分片数量({})不匹配: 客户端ID={}", orderedFiles.size(), totalChunks, clientId);
            return null;
        }

        return orderedFiles;
    }

    /**
     * 收集文件哈希值（按分片索引顺序）
     */
    private List<String> collectFileHashes(FileUploadState state) {
        int totalChunks = state.getTotalChunks();
        List<String> orderedHashes = new ArrayList<>(totalChunks);
        Map<String, String> chunkHashes = state.getChunkHashes();

        log.info("---------开始按顺序收集文件哈希值: 客户端ID={}, 总分片数={}------------", state.getClientId(), totalChunks);

        // 按分片索引顺序收集哈希值
        for (int i = 0; i < totalChunks; i++) {
            String chunkKey = "chunk_" + i;
            String hash = chunkHashes.get(chunkKey);

            if (hash != null && !hash.trim().isEmpty()) {
                orderedHashes.add(hash);
                log.debug("收集分片哈希 {}: key={}, hash={}", i, chunkKey, hash);
            } else {
                log.error("分片哈希值缺失或为空: 索引={}, key={}, 客户端ID={}", i, chunkKey, state.getClientId());
                return null;
            }
        }

        // 验证收集的哈希数量
        if (orderedHashes.size() != totalChunks) {
            log.error("收集的哈希数量({})与预期分片数量({})不匹配: 客户端ID={}",
                    orderedHashes.size(), totalChunks, state.getClientId());
            return null;
        }

        log.info("成功按顺序收集了 {} 个分片哈希值: 客户端ID={}", orderedHashes.size(), state.getClientId());
        return orderedHashes;
    }

    /**
     * 生成文件参数
     * 包含解密所需的初始密钥（最后一个分片的密钥）
     */
    private String generateFileParam(FileUploadState state) {
        // 生成文件参数，包含必要的元数据
        Map<String, Object> params = new HashMap<>();
        params.put("fileName", state.getFileName());
        params.put("fileSize", state.getFileSize());
        params.put("contentType", state.getContentType());
        params.put("uploadTime", System.currentTimeMillis());
        params.put("chunkCount", state.getTotalChunks());

        // 存储初始密钥（最后一个分片的密钥）用于前端解密
        // 密钥链设计：chunk[i] 末尾包含 chunk[i+1] 的密钥，最后一个包含 chunk[0] 的密钥
        // 因此解密需要从最后一个分片开始，需要知道其密钥
        int lastChunkIndex = state.getTotalChunks() - 1;
        Map<Integer, byte[]> keys = state.getKeys();
        if (keys != null && keys.containsKey(lastChunkIndex)) {
            byte[] lastChunkKey = keys.get(lastChunkIndex);
            params.put("initialKey", Base64.getEncoder().encodeToString(lastChunkKey));
        } else {
            log.warn("无法获取最后一个分片的密钥: clientId={}, lastChunkIndex={}",
                    state.getClientId(), lastChunkIndex);
        }

        try {
            return JsonConverter.toJson(params);
        } catch (Exception e) {
            log.error("生成文件参数失败: {}", e.getMessage(), e);
            return "{}"; // 返回空参数
        }
    }

    // === 响应创建辅助方法 (Service 内部使用) ===

    private Path getChunkUploadPath(String SUID, String clientId, int chunkNumber) {
        return getUploadSessionDir(SUID, clientId).resolve("chunk_" + chunkNumber);
    }

    private Path getChunkProcessedPath(String SUID, String clientId, int chunkNumber) {
        return getProcessedSessionDir(SUID, clientId).resolve("encrypted_chunk_" + chunkNumber);
    }

    private boolean isValidChunkNumber(int chunkNumber, int totalChunks) {
        if (totalChunks == 0) return chunkNumber == 0; // 0字节文件
        return chunkNumber >= 0 && chunkNumber < totalChunks;
    }

    // === 进度计算辅助方法 ===
    private void updateUploadProgress(FileUploadState state, String reason) {
        long now = System.currentTimeMillis();
        if (now - state.getLastProgressLogTime() >= PROGRESS_UPDATE_INTERVAL_MS) {
            ProgressInfo info = calculateProgressInfo(state);
            log.info("进度更新 ({}) [客户端ID: {}]: 总进度: {}%, 上传: {}/{} ({}%), 处理: {}/{} ({}%)",
                    reason, state.getClientId(), info.totalProgress,
                    info.uploadedCount, info.totalChunks, info.uploadProgressPercent,
                    info.processedCount, info.totalChunks, info.processProgressPercent);
            state.setLastProgressLogTime(now);

            // 更新Redis中的状态
            redisStateManager.updateState(state);
        }
    }

    /**
     * 判断是否为严重的上传异常，需要清理Redis状态
     *
     * @param e 异常对象
     * @return true表示严重异常，需要清理状态；false表示轻微异常，保留状态支持重试
     */
    private boolean isCriticalUploadError(Exception e) {
        // 严重异常类型：需要清理Redis状态
        if (e instanceof SecurityException ||           // 安全异常
                e instanceof NoSuchAlgorithmException ||    // 算法不可用
                e instanceof IllegalStateException) {       // 非法状态
            return true;
        }

        // IO异常中的严重类型
        if (e instanceof IOException) {
            String message = e.getMessage();
            if (message != null) {
                // 磁盘空间不足、权限问题等严重IO异常
                return message.contains("No space left on device") ||
                        message.contains("Permission denied") ||
                        message.contains("Access is denied");
            }
        }

        // 其他异常视为轻微异常，保留状态支持重试
        return false;
    }

    /**
     * 内部类，用于封装进度计算结果
     */
    private record ProgressInfo(int totalChunks, int uploadedCount, int processedCount, int uploadProgressPercent,
                                int processProgressPercent, int totalProgress) {
    }
}
