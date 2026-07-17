package cn.flying.fisco_bcos.adapter.impl;

import cn.flying.fisco_bcos.config.BsnBesuConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BsnBesuFileNonceStateStoreTest {

    private static final long CHAIN_ID = 1337L;
    private static final String FIRST_PRIVATE_KEY = "1".repeat(64);
    private static final Credentials FIRST_CREDENTIALS =
            Credentials.create(FIRST_PRIVATE_KEY);
    private static final Credentials SECOND_CREDENTIALS =
            Credentials.create("2".repeat(64));

    @TempDir
    private Path stateDirectory;

    /**
     * 验证相同 chainId/signer 的第二个进程视图无法同时取得 writer 所有权。
     */
    @Test
    void shouldRejectSecondWriterForSameChainAndSigner() {
        BsnBesuFileNonceStateStore first = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        BsnBesuFileNonceStateStore second = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        first.initialize();
        try {
            assertThatThrownBy(second::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("另一个 writer");
        } finally {
            second.close();
            first.close();
        }
    }

    /**
     * 验证独立 JVM 已持有相同 chainId/signer 文件锁时，当前 JVM 的 writer 启动失败。
     */
    @Test
    void shouldRejectWriterOwnedByForkedJvm() throws Exception {
        Path readyFile = stateDirectory.resolve("forked-writer.ready");
        Path processLog = stateDirectory.resolve("forked-writer.log");
        Process child = startWriterProcess(readyFile, processLog);
        BsnBesuFileNonceStateStore competingStore =
                newStore(CHAIN_ID, FIRST_CREDENTIALS);
        try {
            awaitWriterReady(child, readyFile, processLog, Duration.ofSeconds(10));

            assertThatThrownBy(competingStore::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("另一个 writer");

            child.getOutputStream().close();
            assertThat(child.waitFor(5, TimeUnit.SECONDS))
                    .as("forked writer should exit after stdin closes; log=%s", readProcessLog(processLog))
                    .isTrue();
            assertThat(child.exitValue())
                    .as("forked writer exit code; log=%s", readProcessLog(processLog))
                    .isZero();
        } finally {
            competingStore.close();
            terminateProcess(child);
        }
    }

    /**
     * 验证旧 writer 释放锁后冷备可接管并恢复 durable high watermark。
     */
    @Test
    void shouldRestoreStateAfterControlledWriterTakeover() throws Exception {
        BsnBesuFileNonceStateStore first = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        first.initialize();
        String signer = FIRST_CREDENTIALS.getAddress();
        BsnBesuNonceStateStore.PersistedState expected = new BsnBesuNonceStateStore.PersistedState(
                BigInteger.valueOf(18),
                BigInteger.valueOf(17),
                BsnBesuNonceStateStore.Outcome.UNKNOWN,
                "0x" + "a".repeat(64),
                null
        );
        first.save(signer, expected);
        first.close();

        BsnBesuFileNonceStateStore takeover = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        try {
            takeover.initialize();
            assertThat(takeover.load(signer)).isEqualTo(expected);
        } finally {
            takeover.close();
        }
    }

    /**
     * 验证 UNKNOWN 广播跨进程重启后仍由真实文件状态阻止 nonce 重用。
     */
    @Test
    void shouldBlockUnknownNonceReuseAfterProcessRestart() throws Exception {
        BsnBesuFileNonceStateStore firstStore = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        firstStore.initialize();
        BsnBesuNonceCoordinator firstCoordinator = new BsnBesuNonceCoordinator(firstStore);
        assertThatThrownBy(() -> firstCoordinator.send(
                FIRST_CREDENTIALS.getAddress(),
                () -> BigInteger.valueOf(25),
                nonce -> "0x25",
                raw -> {
                    throw new IOException("broadcast timeout");
                }
        )).isInstanceOf(IOException.class);
        firstStore.close();

        BsnBesuFileNonceStateStore restartedStore = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        try {
            restartedStore.initialize();
            BsnBesuNonceCoordinator restartedCoordinator =
                    new BsnBesuNonceCoordinator(restartedStore);
            assertThatThrownBy(() -> restartedCoordinator.send(
                    FIRST_CREDENTIALS.getAddress(),
                    () -> BigInteger.valueOf(25),
                    nonce -> "0x26",
                    BsnBesuFileNonceStateStoreTest::accepted
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unresolved broadcast");
        } finally {
            restartedStore.close();
        }
    }

    /**
     * 验证不同 signer 在同一持久目录中使用独立锁和状态文件。
     */
    @Test
    void shouldAllowDifferentSignersToOwnIndependentLocks() {
        BsnBesuFileNonceStateStore first = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        BsnBesuFileNonceStateStore second = newStore(CHAIN_ID, SECOND_CREDENTIALS);
        try {
            first.initialize();
            assertThatCode(second::initialize).doesNotThrowAnyException();
        } finally {
            second.close();
            first.close();
        }
    }

    /**
     * 验证相同 signer 在不同 chainId 下不会共享 writer 锁或 nonce 状态。
     */
    @Test
    void shouldIsolateSameSignerAcrossChains() {
        BsnBesuFileNonceStateStore first = newStore(CHAIN_ID, FIRST_CREDENTIALS);
        BsnBesuFileNonceStateStore second = newStore(CHAIN_ID + 1, FIRST_CREDENTIALS);
        try {
            first.initialize();
            assertThatCode(second::initialize).doesNotThrowAnyException();
        } finally {
            second.close();
            first.close();
        }
    }

    /**
     * 验证损坏的 durable state 会阻止 writer 启动而不是静默清空高水位。
     */
    @Test
    void shouldFailClosedForCorruptedStateFile() throws Exception {
        Path stateFile = stateDirectory.resolve(
                "chain-" + CHAIN_ID + "-signer-"
                        + FIRST_CREDENTIALS.getAddress().substring(2)
                        + ".state"
        );
        Files.writeString(
                stateFile,
                "schema=" + BsnBesuFileNonceStateStore.SCHEMA_VERSION + "\nnextNonce=broken\n",
                StandardCharsets.UTF_8
        );
        BsnBesuFileNonceStateStore store = newStore(CHAIN_ID, FIRST_CREDENTIALS);

        try {
            assertThatThrownBy(store::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("字段不完整");
        } finally {
            store.close();
        }
    }

    /**
     * 验证字段完整但 ACCEPTED 高水位未推进的语义损坏状态会阻止 writer 启动。
     */
    @Test
    void shouldFailClosedForAcceptedStateWithoutAdvancedHighWatermark() throws Exception {
        Path stateFile = stateDirectory.resolve(
                "chain-" + CHAIN_ID + "-signer-"
                        + FIRST_CREDENTIALS.getAddress().substring(2)
                        + ".state"
        );
        Files.writeString(
                stateFile,
                "schema=" + BsnBesuFileNonceStateStore.SCHEMA_VERSION + "\n"
                        + "chainId=" + CHAIN_ID + "\n"
                        + "signer=" + FIRST_CREDENTIALS.getAddress().toLowerCase() + "\n"
                        + "nextNonce=31\n"
                        + "lastNonce=31\n"
                        + "outcome=ACCEPTED\n"
                        + "localTransactionHash=0x" + "a".repeat(64) + "\n"
                        + "remoteTransactionHash=0x" + "a".repeat(64) + "\n"
                        + "updatedAt=" + Instant.parse("2026-07-17T00:00:00Z") + "\n",
                StandardCharsets.UTF_8
        );
        BsnBesuFileNonceStateStore store = newStore(CHAIN_ID, FIRST_CREDENTIALS);

        try {
            assertThatThrownBy(store::initialize)
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            store.close();
        }
    }

    /**
     * 验证 BSN Besu 模式缺少持久状态目录时启动失败关闭。
     */
    @Test
    void shouldRequireNonceStateDirectory() {
        BsnBesuConfig config = new BsnBesuConfig();
        config.setChainId(CHAIN_ID);
        BsnBesuFileNonceStateStore store =
                new BsnBesuFileNonceStateStore(config, FIRST_CREDENTIALS);

        assertThatThrownBy(store::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("state directory 未配置");
    }

    /**
     * 创建绑定到临时状态目录的文件存储实例。
     */
    private BsnBesuFileNonceStateStore newStore(long chainId, Credentials credentials) {
        BsnBesuConfig config = new BsnBesuConfig();
        config.setChainId(chainId);
        config.getNonce().setStateDirectory(stateDirectory.toString());
        return new BsnBesuFileNonceStateStore(config, credentials);
    }

    /**
     * 启动独立 JVM，并把输出重定向到测试专用日志文件。
     */
    private Process startWriterProcess(Path readyFile, Path processLog) throws IOException {
        String classPath = System.getProperty("surefire.test.class.path");
        if (classPath == null || classPath.isBlank()) {
            classPath = System.getProperty("java.class.path");
        }
        if (classPath == null || classPath.isBlank()) {
            throw new IllegalStateException("Forked JVM classpath is unavailable");
        }

        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(classPath);
        command.add(BsnBesuWriterLockProcess.class.getName());
        command.add(stateDirectory.toString());
        command.add(String.valueOf(CHAIN_ID));
        command.add(FIRST_PRIVATE_KEY);
        command.add(readyFile.toString());

        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(processLog.toFile())
                .start();
    }

    /**
     * 在有界时间内等待子进程确认锁已获取，并在提前退出时附带日志失败。
     */
    private void awaitWriterReady(
            Process child,
            Path readyFile,
            Path processLog,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(readyFile)) {
                return;
            }
            if (!child.isAlive()) {
                throw new AssertionError(
                        "Forked writer exited before acquiring lock, exit="
                                + child.exitValue() + ", log=" + readProcessLog(processLog)
                );
            }
            Thread.sleep(25);
        }
        throw new AssertionError(
                "Timed out waiting for forked writer lock; log=" + readProcessLog(processLog)
        );
    }

    /**
     * 先请求子进程正常退出，超时后逐级终止并确保不遗留进程。
     */
    private void terminateProcess(Process child) throws Exception {
        if (child == null || !child.isAlive()) {
            return;
        }
        child.getOutputStream().close();
        if (child.waitFor(2, TimeUnit.SECONDS)) {
            return;
        }
        child.destroy();
        if (child.waitFor(2, TimeUnit.SECONDS)) {
            return;
        }
        child.destroyForcibly();
        assertThat(child.waitFor(5, TimeUnit.SECONDS))
                .as("forked writer must terminate during test cleanup")
                .isTrue();
    }

    /**
     * 读取子进程日志，日志尚未创建时返回稳定占位文本。
     */
    private String readProcessLog(Path processLog) {
        try {
            return Files.exists(processLog)
                    ? Files.readString(processLog, StandardCharsets.UTF_8)
                    : "<log-not-created>";
        } catch (IOException exception) {
            return "<log-unreadable:" + exception.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 判断当前 JVM 是否运行在 Windows，以解析 java 可执行文件名。
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win");
    }

    /**
     * 创建与签名 payload 本地哈希一致的成功广播响应。
     */
    private static EthSendTransaction accepted(String signedRawTransaction) {
        EthSendTransaction response = new EthSendTransaction();
        response.setResult(Hash.sha3(signedRawTransaction));
        return response;
    }
}
