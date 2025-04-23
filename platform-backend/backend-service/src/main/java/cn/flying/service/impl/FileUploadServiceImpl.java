package cn.flying.service.impl;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.file.*;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @program: RecordPlatform
 * @description: 文件上传服务 (包含业务逻辑、状态管理、文件操作等)
 * @author: flyingcoding
 * @create: 2025-03-31 11:22 (Refactored: YYYY-MM-DD)
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
    private static final int PROGRESS_UPDATE_INTERVAL_MS = 1000; // 进度日志更新间隔（毫秒）
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$");
    private static final String HASH_ALGORITHM = "SHA-256"; // 哈希算法
    private static final String HASH_SEPARATOR = "\n--HASH--\n"; // 哈希值前的分隔符
    private static final String KEY_SEPARATOR = "\n--NEXT_KEY--\n"; // 下一个密钥前的分隔符

    // --- 加密常量 ---
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"; // 加密算法/模式/填充
    private static final String KEY_ALGORITHM = "AES"; // 密钥生成算法
    private static final int IV_SIZE_BYTES = 12; // IV 大小 (GCM推荐12字节)
    private static final int KEY_SIZE_BITS = 256; // 密钥大小 (256位)
    private static final int TAG_BIT_LENGTH = 128; // GCM 认证标签长度 (128位)

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

    // --- 上传状态管理 ---
    private final Map<String, FileUploadState> activeUploads = new ConcurrentHashMap<>(); // 活跃的上传会话
    private final Map<String, String> fileClientKeyToSessionId = new ConcurrentHashMap<>(); // 映射: "fileName_clientId" -> sessionId
    private final Set<String> pausedSessionIds = ConcurrentHashMap.newKeySet(); // 暂停的会话ID集合

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
            log.info("基础上传和处理目录已确保存在。");
        } catch (IOException e) {
            log.error("初始化基础目录失败", e);
            // 初始化失败是严重问题，可以抛出运行时异常阻止应用启动
            throw new RuntimeException("创建基础目录失败", e);
        }
        // 启动定时清理任务
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredUploadSessions, 1, 1, TimeUnit.HOURS);
        log.info("定时清理任务已启动。");
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

    // === Service 方法实现 ===

    /**
     * 处理开始上传请求
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     */
    public StartUploadVO startUpload(String fileName, long fileSize, String contentType, String providedClientId) {

        String clientId = (providedClientId == null || providedClientId.trim().isEmpty())
                ? UUID.randomUUID().toString() : providedClientId;
        String fileClientKey = fileName + "_" + clientId;

        log.info("处理上传开始请求: 文件名={}, 文件大小={}, 内容类型={}, 客户端ID={}",
                fileName, fileSize, contentType, clientId);

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
        if (!isFileTypeAllowed(fileName, contentType)) {
            throw new GeneralException("不支持的文件类型");
        }

        // --- 检查是否可恢复 ---
        String existingSessionId = fileClientKeyToSessionId.get(fileClientKey);
        if (existingSessionId != null) {
            FileUploadState existingState = activeUploads.get(existingSessionId);
            if (existingState != null && existingState.getFileSize() == fileSize) {
                log.info("发现可恢复的上传会话: 会话ID={}, 文件客户端键={}", existingSessionId, fileClientKey);
                pausedSessionIds.remove(existingSessionId); // 恢复会话（如果之前暂停了）
                existingState.updateLastActivity();
                // 返回恢复成功的 DTO
                return createResumeDto(existingState);
            } else {
                log.warn("发现旧会话但无法恢复 (状态丢失或文件大小不匹配): 旧会话ID={}, 文件客户端键={}", existingSessionId, fileClientKey);
                fileClientKeyToSessionId.remove(fileClientKey);
                if(existingState != null) {
                    cleanupUploadSessionInternal(existingSessionId); // 主动清理旧状态
                }
            }
        }

        // --- 创建新会话 ---
        try {
            FileUploadState newState = new FileUploadState(fileName, fileSize, contentType, clientId);
            activeUploads.put(newState.getSessionId(), newState);
            fileClientKeyToSessionId.put(fileClientKey, newState.getSessionId());

            // 确保客户端和会话的目录存在
            Files.createDirectories(getUploadSessionDir(clientId, newState.getSessionId()));
            Files.createDirectories(getProcessedSessionDir(clientId, newState.getSessionId()));

            log.info("创建新的上传会话: 会话ID={}, 文件客户端键={}", newState.getSessionId(), fileClientKey);
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
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传已暂停
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     * @throws GeneralException 安全相关操作失败 (如哈希)
     */
    public void uploadChunk(String sessionId, int chunkNumber, MultipartFile file) {

        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或已过期: 会话ID=" + sessionId);
        }
        if (pausedSessionIds.contains(sessionId)) {
            throw new GeneralException("上传已暂停，请先恢复上传: 会话ID=" + sessionId);
        }

        state.updateLastActivity(); // 更新活动时间

        // 注意: totalChunksParam 从 Controller 传来，但我们应使用 state 中的 totalChunks 进行验证
        if (!isValidChunkNumber(chunkNumber, state.getTotalChunks())) {
            throw new GeneralException("无效的分片序号: 序号=" + chunkNumber + ", 总数=" + state.getTotalChunks());
        }
        if (file.isEmpty()) {
            throw new GeneralException("上传的分片不能为空: 会话ID=" + sessionId + ", 序号=" + chunkNumber);
        }

        // --- 优化：边保存边计算哈希 ---
        Path chunkPath = getChunkUploadPath(state.getClientId(), sessionId, chunkNumber);
        String calculatedHashBase64;

        try {
            // 检查是否已处理
            if (state.getProcessedChunks().contains(chunkNumber)) {
                log.info("分片 {} 已处理过，跳过: 会话ID={}", chunkNumber, sessionId);
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
                log.warn("写入字节数 ({}) 与文件大小 ({}) 不符: 分片={}, 会话={}",
                        bytesWritten, file.getSize(), chunkNumber, sessionId);
                // 可以考虑是否需要抛异常
            }

            byte[] hashBytes = digest.digest();
            calculatedHashBase64 = Base64.getEncoder().encodeToString(hashBytes);

            log.info("分片 {} 保存成功: 会话ID={}, 大小={}, 哈希={}", chunkNumber, sessionId, bytesWritten, calculatedHashBase64);
            state.addUploadedChunk(chunkNumber);
            state.addChunkHash("chunk_" + chunkNumber, calculatedHashBase64);

            // --- 触发异步处理 ---
            processChunkImmediately(state, chunkNumber, chunkPath, calculatedHashBase64);

            updateUploadProgress(state, "上传分片 " + chunkNumber);

        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法 {} 不可用!", HASH_ALGORITHM, e);
            tryDelete(chunkPath);
            state.getUploadedChunks().remove(chunkNumber); // 回滚状态
            throw new GeneralException("内部服务器错误：哈希算法不可用");
        } catch (IOException e) {
            log.error("保存或哈希分片 {} 失败: 会话ID={}", chunkNumber, sessionId, e);
            tryDelete(chunkPath);
            state.getUploadedChunks().remove(chunkNumber);
            throw new GeneralException("保存分片失败: " + e.getMessage());
        } catch (Exception e) { // 捕获其他潜在异常
            log.error("处理分片 {} 时发生未知错误: 会话ID={}", chunkNumber, sessionId, e);
            tryDelete(chunkPath);
            state.getUploadedChunks().remove(chunkNumber);
            throw new RuntimeException("处理分片时发生未知错误: " + e.getMessage(), e); // 或者自定义异常
        }
    }

    /**
     * 完成文件上传处理
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传未完成（分片未处理完）
     * @throws GeneralException IO 操作失败
     */
    public void completeUpload(String sessionId){

        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或已过期: 会话ID=" + sessionId);
        }

        log.info("处理完成上传请求: 会话ID={}, 文件名={}", sessionId, state.getFileName());
        state.updateLastActivity();

        // 检查是否所有分片都已处理
        int expectedChunks = state.getTotalChunks();
        boolean allProcessed = state.getProcessedChunks().size() == expectedChunks;

        if (!allProcessed) {
            log.warn("请求完成上传时，并非所有分片都已处理: 会话ID={}, 已处理={}, 总数={}",
                    sessionId, state.getProcessedChunks().size(), expectedChunks);

            // 尝试等待一小段时间，让可能正在进行的异步处理完成
            try {
                TimeUnit.SECONDS.sleep(3); // 可调整等待时间
                allProcessed = state.getProcessedChunks().size() == expectedChunks; // 重新检查
                if (!allProcessed) {
                    log.error("等待后，仍有分片未处理完成: 会话ID={}", sessionId);
                    throw new GeneralException("部分分片未处理完成，请稍后重试或检查状态");
                }
                log.info("等待后，所有分片处理完成: 会话ID={}", sessionId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("等待分片处理完成时被中断: 会话ID={}", sessionId, e);
                // 可以抛出特定异常或包装IO异常
                throw new GeneralException("处理被中断");
            }
        }

        try {
            // --- 执行最终步骤 (追加下一个分片的密钥) ---
            completeFileProcessing(state);

            // --- 清理原始上传分片 ---
            Path uploadSessionDir = getUploadSessionDir(state.getClientId(), sessionId);
            cleanupDirectory(uploadSessionDir);
            log.info("原始上传分片目录已清理: {}", uploadSessionDir);

            // --- 清理上传状态 (从内存中移除) ---
            activeUploads.remove(sessionId);
            fileClientKeyToSessionId.remove(state.getFileName() + "_" + state.getClientId());
            pausedSessionIds.remove(sessionId); // 确保移除暂停状态

            // 从MDC中获取用户ID
            String Uid = MDC.get("userId");
            // 调用文件服务初始化文件元信息
            fileService.prepareStoreFile(Uid, state.getFileName());

            log.info("文件上传和处理流程完成: 会话ID={}, 文件名={}", sessionId, state.getFileName());

            //todo 消息队列实现文件异步存证
        } catch (IOException e) {
            log.error("完成文件处理或清理时失败: 会话ID={}", sessionId, e);
            // 考虑更具体的错误处理或回滚机制
            throw new GeneralException("完成处理失败：" + e.getMessage());
        } catch (Exception e) { // 捕获其他潜在异常
            log.error("完成文件处理时发生未知错误: 会话ID={}", sessionId, e);
            throw new RuntimeException("完成处理时发生未知错误：" + e.getMessage(), e);
        }
    }

    /**
     * 暂停上传
     * @throws GeneralException 会话不存在
     */
    public void pauseUpload(String sessionId){
        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 会话ID=" + sessionId);
        }

        pausedSessionIds.add(sessionId);
        state.updateLastActivity();
        log.info("上传会话已暂停: 会话ID={}", sessionId);
    }

    /**
     * 恢复上传
     * @throws GeneralException 会话不存在
     */
    public ResumeUploadVO resumeUpload(String sessionId){
        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 会话ID=" + sessionId);
        }

        boolean wasPaused = pausedSessionIds.remove(sessionId);
        state.updateLastActivity();

        // 创建包含已处理分片列表的恢复响应 DTO
        ResumeUploadVO responseDto = new ResumeUploadVO(
                true,
                wasPaused ? "上传已恢复" : "上传未被暂停",
                new ArrayList<>(state.getProcessedChunks()), // 返回已处理的分片列表给客户端
                state.getTotalChunks()
        );

        log.info("上传会话已恢复 (之前是否暂停={}): 会话ID={}", wasPaused, sessionId);
        return responseDto;
    }

    /**
     * 取消上传并清理资源
     * @return 如果找到并清理了会话则返回 true，否则返回 false
     */
    public boolean cancelUpload(String sessionId) {
        log.info("收到取消上传请求: 会话ID={}", sessionId);
        return cleanupUploadSessionInternal(sessionId); // 内部方法处理查找和清理
    }

    /**
     * 检查文件上传状态
     * @throws GeneralException 会话不存在
     */
    public FileUploadStatusVO checkFileStatus(String sessionId){
        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在或已完成: 会话ID=" + sessionId);
        }

        state.updateLastActivity();
        boolean isPaused = pausedSessionIds.contains(sessionId);
        ProgressInfo progressInfo = calculateProgressInfo(state);
        String statusMessage;
        String statusCode;

        if (isPaused) {
            statusMessage = "上传已暂停";
            statusCode = "PAUSED";
        } else if (progressInfo.processedCount == progressInfo.totalChunks && progressInfo.totalChunks > 0) {
            statusMessage = "所有分片处理完成，等待最终确认";
            statusCode = "PROCESSING_COMPLETE";
        } else {
            statusMessage = "文件正在上传/处理中";
            statusCode = "UPLOADING";
        }

        FileUploadStatusVO responseDto = new FileUploadStatusVO(
                state.getFileName(), state.getFileSize(), sessionId, state.getClientId(),
                isPaused, statusCode, progressInfo.totalProgress,
                new ArrayList<>(state.getProcessedChunks()),
                progressInfo.processedCount, progressInfo.totalChunks
        );

        log.debug("检查状态成功: 会话ID={}, 状态={}, 进度={}%", sessionId, statusCode, progressInfo.totalProgress);
        return responseDto;
    }

    /**
     * 获取上传进度
     * @throws GeneralException 会话不存在
     */
    public ProgressVO getUploadProgress(String sessionId){
        FileUploadState state = activeUploads.get(sessionId);
        if (state == null) {
            throw new GeneralException("上传会话不存在: 会话ID=" + sessionId);
        }

        state.updateLastActivity();
        ProgressInfo progressInfo = calculateProgressInfo(state);

        ProgressVO responseDto = new ProgressVO(
                true, "获取进度成功", progressInfo.totalProgress,
                progressInfo.uploadProgressPercent, progressInfo.processProgressPercent,
                progressInfo.uploadedCount, progressInfo.processedCount, progressInfo.totalChunks,
                sessionId
        );

        log.debug("获取进度成功: 会话ID={}, 总进度={}%", sessionId, progressInfo.totalProgress);
        return responseDto;
    }


    // === 私有辅助方法 ===

    /**
     * 异步处理已上传的分片：对其进行加密，并在末尾附加其原始哈希值。
     */
    private void processChunkImmediately(FileUploadState state, int chunkNumber, Path chunkPath, String chunkHashBase64) {
        CompletableFuture.runAsync(() -> {
            Path processedChunkPath = getChunkProcessedPath(state.getClientId(), state.getSessionId(), chunkNumber);
            log.debug("开始异步处理分片 {}: 原始路径={}", chunkNumber, chunkPath);

            try {
                // 1. 生成密钥和 IV
                SecretKey chunkSecretKey = generateChunkKey();
                byte[] iv = generateIV();
                GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
                state.getKeys().put(chunkNumber, chunkSecretKey.getEncoded());

                // 2. 初始化密码器
                Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
                cipher.init(Cipher.ENCRYPT_MODE, chunkSecretKey, gcmSpec);

                // 3. 加密并写入
                Files.createDirectories(processedChunkPath.getParent());
                try (InputStream fis = Files.newInputStream(chunkPath);
                     OutputStream fos = Files.newOutputStream(processedChunkPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                    fos.write(iv); // 写入 IV
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] encryptedBytes = cipher.update(buffer, 0, bytesRead);
                        if (encryptedBytes != null) fos.write(encryptedBytes);
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) fos.write(finalBytes);

                    // 追加哈希
                    fos.write(HASH_SEPARATOR.getBytes(StandardCharsets.UTF_8));
                    fos.write(chunkHashBase64.getBytes(StandardCharsets.UTF_8));
                }

                // 4. 标记处理完成
                state.addProcessedChunk(chunkNumber);
                updateUploadProgress(state, "处理完分片 " + chunkNumber);
                log.info("分片 {} 处理成功: 会话ID={}, 处理后路径={}",
                        chunkNumber, state.getSessionId(), processedChunkPath);

            } catch (Exception e) {
                log.error("异步处理分片 {} 失败: 会话ID={}", chunkNumber, state.getSessionId(), e);
                state.getProcessedChunks().remove(chunkNumber); // 回滚状态
                state.getKeys().remove(chunkNumber);
                tryDelete(processedChunkPath);
                // 可以在这里考虑通知机制或记录失败状态
            }
        }, fileProcessingExecutor);
    }

    /**
     * 文件处理的最后一步：追加下一个分片的密钥。
     */
    private void completeFileProcessing(FileUploadState state) throws IOException {
        log.info("开始最终处理步骤 (追加下一个分片密钥): 会话ID={}", state.getSessionId());
        int totalChunks = state.getTotalChunks();
        Map<Integer, byte[]> keys = state.getKeys();

        if (keys.size() < totalChunks) {
            log.error("密钥数量 ({}) 少于分片总数 ({}), 会话ID={}. 无法完成处理。", keys.size(), totalChunks, state.getSessionId());
            throw new IOException("无法完成处理：并非所有分片的密钥都可用。");
        }
        for(int i=0; i < totalChunks; i++){
            if(keys.get(i) == null){
                log.error("分片 {} 的密钥丢失 (为 null), 会话ID={}. 无法完成处理。", i, state.getSessionId());
                throw new IOException("无法完成处理：分片 " + i + " 的密钥丢失。");
            }
        }

        for (int i = 0; i < totalChunks - 1; i++) {
            Path currentChunkPath = getChunkProcessedPath(state.getClientId(), state.getSessionId(), i);
            byte[] nextChunkKey = keys.get(i + 1);
            appendKeyToFile(currentChunkPath, nextChunkKey, i);
        }

        if (totalChunks > 0) {
            int lastChunkIndex = totalChunks - 1;
            Path lastChunkPath = getChunkProcessedPath(state.getClientId(), state.getSessionId(), lastChunkIndex);
            byte[] firstChunkKey = keys.get(0);
            appendKeyToFile(lastChunkPath, firstChunkKey, lastChunkIndex);
        }

        log.info("最终处理步骤 (追加下一个分片密钥) 完成: 会话ID={}", state.getSessionId());
    }

    /** 辅助方法：追加密钥到文件 */
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

    /** 生成一个新的 AES 密钥 */
    private SecretKey generateChunkKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGen.init(KEY_SIZE_BITS);
        return keyGen.generateKey();
    }

    /** 生成一个随机的 IV */
    private byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    /** 定时任务：清理过期的上传会话 */
    @Scheduled(fixedRate = 3600000) // 每小时执行一次
    public void cleanupExpiredUploadSessions() {
        long now = System.currentTimeMillis();
        long timeoutMillis = 24 * 60 * 60 * 1000L; // 24 小时
        log.info("开始执行定时清理任务，查找超过 {} 小时未活动的上传会话...", TimeUnit.MILLISECONDS.toHours(timeoutMillis));

        List<String> expiredSessionIds = new ArrayList<>();
        activeUploads.forEach((sessionId, state) -> {
            // 如果会话被暂停，可以考虑使用更长的超时时间或不同的策略
            // if (pausedSessionIds.contains(sessionId)) { ... }
            long inactiveDuration = now - state.getLastActivityTime();
            if (inactiveDuration >= timeoutMillis) {
                expiredSessionIds.add(sessionId);
                log.warn("发现过期上传会话: 会话ID={}, 文件名={}, 上次活动时间={}",
                        sessionId, state.getFileName(), Instant.ofEpochMilli(state.getLastActivityTime()));
            }
        });

        int cleanedCount = 0;
        for (String sessionId : expiredSessionIds) {
            if (cleanupUploadSessionInternal(sessionId)) {
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("定时清理完成，共清理 {} 个过期上传会话。", cleanedCount);
        } else {
            log.info("定时清理完成，未发现需要清理的过期上传会话。");
        }
    }

    /** 内部方法：清理指定 sessionId 的上传状态和相关文件目录 */
    private boolean cleanupUploadSessionInternal(String sessionId) {
        FileUploadState state = activeUploads.remove(sessionId); // 从活跃会话中移除
        if (state != null) {
            // 从其他映射中移除
            fileClientKeyToSessionId.remove(state.getFileName() + "_" + state.getClientId());
            pausedSessionIds.remove(sessionId); // 确保从暂停集合中移除

            log.info("开始清理会话 {} 的相关文件: 客户端ID={}", sessionId, state.getClientId());

            // 使用线程池异步清理文件，避免阻塞当前线程（特别是定时任务线程）
            fileProcessingExecutor.submit(() -> {
                // 清理原始上传目录
                Path uploadDir = getUploadSessionDir(state.getClientId(), sessionId);
                cleanupDirectory(uploadDir);
                // 清理处理后的目录
                Path processedDir = getProcessedSessionDir(state.getClientId(), sessionId);
                cleanupDirectory(processedDir);
                log.info("会话 {} 文件清理完成。", sessionId);
            });

            return true; // 状态已找到并开始清理流程
        } else {
            // 尝试从暂停集合中移除，以防状态已从 activeUploads 中移除但仍在 pausedSessionIds 中
            boolean removedFromPaused = pausedSessionIds.remove(sessionId);
            if (removedFromPaused) {
                log.warn("清理会话 {} 时，状态已不在 activeUploads 中，但从 pausedSessionIds 移除成功。", sessionId);
                // 这里可以根据需要决定是否尝试清理文件，但缺少 clientId 信息会比较困难
            } else {
                log.debug("尝试清理会话 {}，但状态已不存在。", sessionId);
            }
            return false; // 会话状态未找到
        }
    }

    /** 递归删除目录及其内容 */
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

    /** 尝试删除路径，记录错误但不抛出 */
    private void tryDelete(Path path) {
        try {
            Files.delete(path);
            // logger.trace("已删除: {}", path); // 可以用更低的日志级别
        } catch (NoSuchFileException e) {
            // Ignore
        } catch (DirectoryNotEmptyException e) {
            log.warn("无法删除非空目录 (可能存在并发访问或延迟): {}", path);
        } catch (IOException e) {
            log.error("删除路径失败: {}", path, e);
        }
    }

    // === 路径构建辅助方法 ===
    private Path getUploadSessionDir(String clientId, String sessionId) {
        return Paths.get(UPLOAD_BASE_DIR, sanitizePathComponent(clientId), sanitizePathComponent(sessionId)).toAbsolutePath().normalize();
    }
    private Path getProcessedSessionDir(String clientId, String sessionId) {
        return Paths.get(PROCESSED_BASE_DIR, sanitizePathComponent(clientId), sanitizePathComponent(sessionId)).toAbsolutePath().normalize();
    }
    private Path getChunkUploadPath(String clientId, String sessionId, int chunkNumber) {
        return getUploadSessionDir(clientId, sessionId).resolve("chunk_" + chunkNumber);
    }
    private Path getChunkProcessedPath(String clientId, String sessionId, int chunkNumber) {
        return getProcessedSessionDir(clientId, sessionId).resolve("encrypted_chunk_" + chunkNumber);
    }
    private String sanitizePathComponent(String component) {
        String sanitized = component.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.isEmpty() || sanitized.matches("\\.+")) return "_" + UUID.randomUUID().toString().substring(0, 8);
        int maxLength = 64;
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }

    // === 验证辅助方法 ===
    private boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        if (fileName.contains("/") || fileName.contains("\\")) return false;
        if (containsPotentiallyUnsafeCharacters(fileName)) return false;
        return SAFE_FILENAME_PATTERN.matcher(fileName).matches();
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
    private String getFileExtension(String fileName) {
        if (fileName == null) return null;
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0 && dotIndex < fileName.length() - 1) ? fileName.substring(dotIndex + 1).toLowerCase() : null;
    }
    private boolean isValidChunkNumber(int chunkNumber, int totalChunks) {
        if (totalChunks == 0) return chunkNumber == 0; // 0字节文件
        return chunkNumber >= 0 && chunkNumber < totalChunks;
    }

    // === 响应创建辅助方法 (Service 内部使用) ===

    // 创建 /upload/start 的新会话响应 DTO
    private StartUploadVO createNewSessionDto(FileUploadState state) {
        return new StartUploadVO(
                state.getSessionId(), state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                Collections.emptyList(), // 新会话没有已处理的分片
                false // 标记为非恢复
        );
    }

    // 创建 /upload/start 的恢复会话响应 DTO
    private StartUploadVO createResumeDto(FileUploadState state) {
        // 注意：恢复时返回的是已 *处理* (processed) 的分片列表，因为客户端需要知道哪些不需要再上传和处理
        return new StartUploadVO(state.getSessionId(), state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                new ArrayList<>(state.getProcessedChunks()), // 返回已处理的分片序号列表
                true // 标记为恢复
        );
    }


    // === 进度计算辅助方法 ===
    private void updateUploadProgress(FileUploadState state, String reason) {
        long now = System.currentTimeMillis();
        if (now - state.getLastProgressLogTime() >= PROGRESS_UPDATE_INTERVAL_MS) {
            ProgressInfo info = calculateProgressInfo(state);
            log.info("进度更新 ({}) [会话ID: {}]: 总进度: {}%, 上传: {}/{} ({}%), 处理: {}/{} ({}%)",
                    reason, state.getSessionId(), info.totalProgress,
                    info.uploadedCount, info.totalChunks, info.uploadProgressPercent,
                    info.processedCount, info.totalChunks, info.processProgressPercent);
            state.setLastProgressLogTime(now);
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

    /**
     * 内部类，用于封装进度计算结果
     */
        private record ProgressInfo(int totalChunks, int uploadedCount, int processedCount, int uploadProgressPercent,
                                    int processProgressPercent, int totalProgress) {
    }
}
