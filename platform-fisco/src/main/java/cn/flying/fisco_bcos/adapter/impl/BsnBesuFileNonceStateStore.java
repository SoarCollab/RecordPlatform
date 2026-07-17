package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.config.BsnBesuConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 使用共享持久目录保存 Besu nonce 状态，并持有 signer 进程生命周期独占锁。
 *
 * <p>文件锁是当前仓库单主机或可靠共享卷部署的单 writer 门禁；不同主机使用独立目录时
 * 无法形成互斥，必须由部署侧 fencing 或不同 signer 保证隔离。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
public final class BsnBesuFileNonceStateStore implements BsnBesuNonceStateStore {

    static final String SCHEMA_VERSION = "record-platform-besu-nonce-state.v1";

    private static final Pattern SIGNER_PATTERN = Pattern.compile("^0x[0-9a-f]{40}$");

    private final BsnBesuConfig besuConfig;
    private final Credentials credentials;
    private final ReentrantLock ioLock = new ReentrantLock();

    private String canonicalSigner;
    private String chainId;
    private Path stateDirectory;
    private Path stateFile;
    private FileChannel ownershipChannel;
    private FileLock ownershipLock;
    private PersistedState persistedState;

    /**
     * 校验持久目录并为当前 chainId/signer 获取独占 writer 锁。
     */
    @PostConstruct
    public void initialize() {
        if (ownershipLock != null && ownershipLock.isValid()) {
            throw new IllegalStateException("[BSN Besu] nonce state store 已初始化");
        }
        Long configuredChainId = besuConfig.getChainId();
        if (configuredChainId == null || configuredChainId < 0) {
            throw new IllegalStateException("[BSN Besu] chainId 必须配置为非负整数");
        }
        this.chainId = String.valueOf(configuredChainId);
        this.canonicalSigner = canonicalizeSigner(credentials.getAddress());

        BsnBesuConfig.NonceConfig nonceConfig = besuConfig.getNonce();
        String configuredDirectory = nonceConfig == null
                ? null
                : nonceConfig.getStateDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new IllegalStateException("[BSN Besu] nonce state directory 未配置");
        }

        try {
            this.stateDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize();
            Files.createDirectories(stateDirectory);
            if (Files.isSymbolicLink(stateDirectory)
                    || !Files.isDirectory(stateDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("[BSN Besu] nonce state directory 必须是真实目录");
            }

            String filePrefix = "chain-" + chainId + "-signer-"
                    + canonicalSigner.substring(2);
            Path ownershipFile = stateDirectory.resolve(filePrefix + ".lock");
            this.stateFile = stateDirectory.resolve(filePrefix + ".state");
            this.ownershipChannel = FileChannel.open(
                    ownershipFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            this.ownershipLock = tryAcquireOwnershipLock(ownershipChannel);
            if (ownershipLock == null) {
                closeOwnershipChannel();
                throw new IllegalStateException(
                        "[BSN Besu] 当前 chainId/signer 已被另一个 writer 持有"
                );
            }
            this.persistedState = readPersistedState();
            log.info(
                    "[BSN Besu nonce] 已获取 signer writer 所有权, chainId={}, signer={}, stateFile={}",
                    chainId,
                    canonicalSigner,
                    stateFile
            );
        } catch (IOException exception) {
            closeOwnershipResources();
            throw new IllegalStateException("[BSN Besu] 无法初始化 nonce 持久状态", exception);
        } catch (RuntimeException exception) {
            closeOwnershipResources();
            throw exception;
        }
    }

    /**
     * 返回当前 signer 已持久化的安全高水位快照。
     */
    @Override
    public PersistedState load(String requestedSigner) throws IOException {
        ioLock.lock();
        try {
            requireOwnedSigner(requestedSigner);
            requireValidOwnershipLock();
            return persistedState;
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * 通过临时文件、force 和原子替换持久化新的 nonce 状态。
     */
    @Override
    public void save(String requestedSigner, PersistedState state) throws IOException {
        ioLock.lock();
        try {
            requireOwnedSigner(requestedSigner);
            requireValidOwnershipLock();
            if (state == null) {
                throw new IllegalArgumentException("Nonce persisted state is required");
            }
            writeStateAtomically(state);
            this.persistedState = state;
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * 释放 signer 文件锁和底层 channel，允许受控冷备接管。
     */
    @PreDestroy
    public void close() {
        ioLock.lock();
        try {
            closeOwnershipResources();
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * 尝试获取非阻塞独占文件锁；同 JVM 重复持有也按冲突处理。
     */
    private FileLock tryAcquireOwnershipLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            return null;
        }
    }

    /**
     * 读取并严格校验已有状态文件；首次启动时返回空状态。
     */
    private PersistedState readPersistedState() throws IOException {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(stateFile)
                || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("[BSN Besu] nonce state file 必须是普通文件");
        }

        Map<String, String> values = parseStateLines(
                Files.readAllLines(stateFile, StandardCharsets.UTF_8)
        );
        requireValue(values, "schema", SCHEMA_VERSION);
        requireValue(values, "chainId", chainId);
        requireValue(values, "signer", canonicalSigner);
        parseUpdatedAt(requireValue(values, "updatedAt"));

        try {
            return new PersistedState(
                    parseNonce(requireValue(values, "nextNonce"), "nextNonce"),
                    parseOptionalNonce(requireValue(values, "lastNonce"), "lastNonce"),
                    parseOutcome(requireValue(values, "outcome")),
                    optionalValue(requireValue(values, "localTransactionHash")),
                    optionalValue(requireValue(values, "remoteTransactionHash"))
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("[BSN Besu] nonce state 语义不一致", exception);
        }
    }

    /**
     * 将状态内容完整写入同目录临时文件，原子替换后同步目录元数据。
     */
    private void writeStateAtomically(PersistedState state) throws IOException {
        String temporaryName = stateFile.getFileName() + ".tmp-" + UUID.randomUUID();
        Path temporaryFile = stateDirectory.resolve(temporaryName);
        byte[] content = serializeState(state).getBytes(StandardCharsets.UTF_8);
        try {
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporaryFile,
                        stateFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
                forceStateDirectory();
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Nonce state directory does not support atomic replacement",
                        exception
                );
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    /**
     * 同步状态目录，确保原子替换的目录项在返回并进入广播前已经持久化。
     */
    private void forceStateDirectory() throws IOException {
        try (FileChannel directoryChannel = FileChannel.open(
                stateDirectory,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        )) {
            directoryChannel.force(true);
        }
    }

    /**
     * 生成不包含私钥或原始签名 payload 的版本化状态文本。
     */
    private String serializeState(PersistedState state) {
        return "schema=" + SCHEMA_VERSION + "\n"
                + "chainId=" + chainId + "\n"
                + "signer=" + canonicalSigner + "\n"
                + "nextNonce=" + state.nextNonce() + "\n"
                + "lastNonce=" + nullableValue(state.lastNonce()) + "\n"
                + "outcome=" + state.outcome().name() + "\n"
                + "localTransactionHash=" + nullableValue(state.localTransactionHash()) + "\n"
                + "remoteTransactionHash=" + nullableValue(state.remoteTransactionHash()) + "\n"
                + "updatedAt=" + Instant.now() + "\n";
    }

    /**
     * 解析固定 key=value 状态格式并拒绝重复或未知字段。
     */
    private Map<String, String> parseStateLines(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalStateException("[BSN Besu] nonce state file 格式错误");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!isKnownStateKey(key) || values.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("[BSN Besu] nonce state file 包含未知或重复字段");
            }
        }
        if (values.size() != 9) {
            throw new IllegalStateException("[BSN Besu] nonce state file 字段不完整");
        }
        return values;
    }

    /**
     * 判断状态字段是否属于受支持的固定 schema。
     */
    private boolean isKnownStateKey(String key) {
        return switch (key) {
            case "schema", "chainId", "signer", "nextNonce", "lastNonce", "outcome",
                    "localTransactionHash", "remoteTransactionHash", "updatedAt" -> true;
            default -> false;
        };
    }

    /**
     * 校验调用者 signer 与启动时独占 signer 完全一致。
     */
    private void requireOwnedSigner(String requestedSigner) {
        if (!canonicalSigner.equals(canonicalizeSigner(requestedSigner))) {
            throw new IllegalArgumentException("Nonce state store does not own the requested signer");
        }
    }

    /**
     * 确认进程仍持有有效 signer 文件锁。
     */
    private void requireValidOwnershipLock() throws IOException {
        if (ownershipLock == null || !ownershipLock.isValid()) {
            throw new IOException("BSN Besu signer ownership lock is not held");
        }
    }

    /**
     * 规范化并校验 Ethereum signer 地址。
     */
    private String canonicalizeSigner(String signer) {
        if (signer == null) {
            throw new IllegalArgumentException("BSN Besu signer address is required");
        }
        String normalized = signer.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("0x")) {
            normalized = "0x" + normalized;
        }
        if (!SIGNER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("BSN Besu signer address is invalid");
        }
        return normalized;
    }

    /**
     * 解析非负十进制 nonce。
     */
    private BigInteger parseNonce(String value, String field) {
        try {
            BigInteger nonce = new BigInteger(value);
            if (nonce.signum() < 0) {
                throw new NumberFormatException("negative nonce");
            }
            return nonce;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("[BSN Besu] nonce state " + field + " 格式错误", exception);
        }
    }

    /**
     * 解析允许为空的 nonce 字段。
     */
    private BigInteger parseOptionalNonce(String value, String field) {
        return value.isEmpty() ? null : parseNonce(value, field);
    }

    /**
     * 解析持久化广播状态枚举。
     */
    private Outcome parseOutcome(String value) {
        try {
            return Outcome.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("[BSN Besu] nonce state outcome 格式错误", exception);
        }
    }

    /**
     * 校验状态更新时间格式。
     */
    private void parseUpdatedAt(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("[BSN Besu] nonce state updatedAt 格式错误", exception);
        }
    }

    /**
     * 读取必填字段。
     */
    private String requireValue(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("[BSN Besu] nonce state 缺少字段 " + key);
        }
        return value;
    }

    /**
     * 校验字段值与当前运行身份一致。
     */
    private void requireValue(Map<String, String> values, String key, String expected) {
        if (!expected.equals(requireValue(values, key))) {
            throw new IllegalStateException("[BSN Besu] nonce state " + key + " 不匹配");
        }
    }

    /**
     * 将空字符串转成 null。
     */
    private String optionalValue(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * 将可空状态值序列化为空字符串或原值。
     */
    private String nullableValue(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * 关闭文件锁和 channel，不删除持久状态或锁文件。
     */
    private void closeOwnershipResources() {
        if (ownershipLock != null) {
            try {
                ownershipLock.release();
            } catch (IOException exception) {
                log.warn("[BSN Besu nonce] 释放 signer 文件锁失败", exception);
            } finally {
                ownershipLock = null;
            }
        }
        closeOwnershipChannel();
    }

    /**
     * 关闭 writer ownership channel。
     */
    private void closeOwnershipChannel() {
        if (ownershipChannel != null) {
            try {
                ownershipChannel.close();
            } catch (IOException exception) {
                log.warn("[BSN Besu nonce] 关闭 signer 锁文件失败", exception);
            } finally {
                ownershipChannel = null;
            }
        }
    }
}
