package cn.flying.fisco_bcos.adapter.impl;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BsnBesuNonceCoordinatorTest {

    private static final String SIGNER = "0x1111111111111111111111111111111111111111";
    private static final String OTHER_SIGNER =
            "0x2222222222222222222222222222222222222222";

    /**
     * 验证广播前本地构造失败不会推进 nonce，后续请求可安全复用。
     */
    @Test
    void shouldReuseNonceAfterLocalSigningFailure() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);

        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(7),
                nonce -> {
                    throw new IOException("local signing failed");
                },
                raw -> accepted(raw)
        )).isInstanceOf(IOException.class)
                .hasMessage("local signing failed");

        AtomicReference<BigInteger> reusedNonce = new AtomicReference<>();
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(7),
                nonce -> {
                    reusedNonce.set(nonce);
                    return "0x01";
                },
                BsnBesuNonceCoordinatorTest::accepted
        );

        assertThat(reusedNonce).hasValue(BigInteger.valueOf(7));
        assertThat(stateStore.load(SIGNER).outcome())
                .isEqualTo(BsnBesuNonceStateStore.Outcome.ACCEPTED);
    }

    /**
     * 验证调用网络广播器前已经持久化 BROADCASTING 安全高水位。
     */
    @Test
    void shouldPersistBroadcastingBeforeCallingNetwork() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        AtomicReference<BsnBesuNonceStateStore.Outcome> observedOutcome =
                new AtomicReference<>();

        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(8),
                nonce -> "0x0102",
                raw -> {
                    observedOutcome.set(stateStore.load(SIGNER).outcome());
                    return accepted(raw);
                }
        );

        assertThat(observedOutcome)
                .hasValue(BsnBesuNonceStateStore.Outcome.BROADCASTING);
        assertThat(stateStore.load(SIGNER).outcome())
                .isEqualTo(BsnBesuNonceStateStore.Outcome.ACCEPTED);
    }

    /**
     * 验证 durable reservation 落盘失败时绝不进入网络广播阶段。
     */
    @Test
    void shouldNotBroadcastWhenDurableReservationFails() {
        AtomicBoolean broadcasterCalled = new AtomicBoolean();
        BsnBesuNonceStateStore failingStore = new BsnBesuNonceStateStore() {
            @Override
            public PersistedState load(String canonicalSigner) {
                return null;
            }

            @Override
            public void save(String canonicalSigner, PersistedState state) throws IOException {
                throw new IOException("durable reservation failed");
            }
        };
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(failingStore);

        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(8),
                nonce -> "0x0103",
                raw -> {
                    broadcasterCalled.set(true);
                    return accepted(raw);
                }
        )).isInstanceOf(IOException.class)
                .hasMessage("durable reservation failed");
        assertThat(broadcasterCalled).isFalse();
    }

    /**
     * 验证标准 JSON-RPC 参数拒绝可在重新读取 PENDING 后复用原 nonce。
     */
    @Test
    void shouldReuseNonceAfterDefiniteReject() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);

        EthSendTransaction rejected = new EthSendTransaction();
        rejected.setError(new Response.Error(-32602, "invalid params"));
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(9),
                nonce -> "0x02",
                raw -> rejected
        );

        AtomicReference<BigInteger> reusedNonce = new AtomicReference<>();
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(9),
                nonce -> {
                    reusedNonce.set(nonce);
                    return "0x03";
                },
                BsnBesuNonceCoordinatorTest::accepted
        );

        assertThat(reusedNonce).hasValue(BigInteger.valueOf(9));
    }

    /**
     * 验证广播 IOException 被持久化为 UNKNOWN，并在 PENDING 未前进时失败关闭。
     */
    @Test
    void shouldBlockReuseUntilPendingPassesUnknownNonce() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);

        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(11),
                nonce -> "0x04",
                raw -> {
                    throw new IOException("connection reset");
                }
        )).isInstanceOf(IOException.class)
                .hasMessage("connection reset");

        assertThat(stateStore.load(SIGNER).outcome())
                .isEqualTo(BsnBesuNonceStateStore.Outcome.UNKNOWN);
        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(11),
                nonce -> "0x05",
                BsnBesuNonceCoordinatorTest::accepted
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manual reconciliation");

        AtomicReference<BigInteger> nextNonce = new AtomicReference<>();
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(12),
                nonce -> {
                    nextNonce.set(nonce);
                    return "0x06";
                },
                BsnBesuNonceCoordinatorTest::accepted
        );

        assertThat(nextNonce).hasValue(BigInteger.valueOf(12));
    }

    /**
     * 验证 already known 不会被误判为可回滚的明确拒绝。
     */
    @Test
    void shouldTreatAlreadyKnownAsUnresolvedBroadcast() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        EthSendTransaction alreadyKnown = new EthSendTransaction();
        alreadyKnown.setError(new Response.Error(-32000, "already known"));

        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(13),
                nonce -> "0x07",
                raw -> alreadyKnown
        );

        assertThat(stateStore.load(SIGNER).outcome())
                .isEqualTo(BsnBesuNonceStateStore.Outcome.UNKNOWN);
        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(13),
                nonce -> "0x08",
                BsnBesuNonceCoordinatorTest::accepted
        )).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 验证同时包含 error 与 result 的冲突响应不能被当作可回滚的明确拒绝。
     */
    @Test
    void shouldTreatConflictingErrorAndResultAsUnknown() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        String signedRawTransaction = "0x0711";
        EthSendTransaction conflicting = responseWithHash(Hash.sha3(signedRawTransaction));
        conflicting.setError(new Response.Error(-32602, "invalid params"));

        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(14),
                nonce -> signedRawTransaction,
                raw -> conflicting
        );

        BsnBesuNonceStateStore.PersistedState state = stateStore.load(SIGNER);
        assertThat(state.outcome()).isEqualTo(BsnBesuNonceStateStore.Outcome.UNKNOWN);
        assertThat(state.nextNonce()).isEqualTo(BigInteger.valueOf(15));
        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(14),
                nonce -> "0x0712",
                BsnBesuNonceCoordinatorTest::accepted
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manual reconciliation");
    }

    /**
     * 验证节点返回与本地签名 payload 不匹配的 hash 时按 UNKNOWN 处理。
     */
    @Test
    void shouldRejectMismatchedTransactionHashWithoutRollback() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);

        assertThatThrownBy(() -> coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(15),
                nonce -> "0x09",
                raw -> responseWithHash("0x" + "a".repeat(64))
        )).isInstanceOf(IOException.class)
                .hasMessageContaining("invalid transaction response");

        BsnBesuNonceStateStore.PersistedState state = stateStore.load(SIGNER);
        assertThat(state.outcome()).isEqualTo(BsnBesuNonceStateStore.Outcome.UNKNOWN);
        assertThat(state.nextNonce()).isEqualTo(BigInteger.valueOf(16));
    }

    /**
     * 验证节点 PENDING 超过本地高水位时选择节点值继续发送。
     */
    @Test
    void shouldAdvanceToNodePendingWhenNodeMovesAhead() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(3),
                nonce -> "0x0a",
                BsnBesuNonceCoordinatorTest::accepted
        );

        AtomicReference<BigInteger> selectedNonce = new AtomicReference<>();
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(20),
                nonce -> {
                    selectedNonce.set(nonce);
                    return "0x0b";
                },
                BsnBesuNonceCoordinatorTest::accepted
        );

        assertThat(selectedNonce).hasValue(BigInteger.valueOf(20));
    }

    /**
     * 验证同一 signer 的大小写形式共享同一 nonce 状态。
     */
    @Test
    void shouldCanonicalizeSignerAddress() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        coordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(30),
                nonce -> "0x0c",
                BsnBesuNonceCoordinatorTest::accepted
        );

        AtomicReference<BigInteger> selectedNonce = new AtomicReference<>();
        coordinator.send(
                SIGNER.toUpperCase(),
                () -> BigInteger.valueOf(30),
                nonce -> {
                    selectedNonce.set(nonce);
                    return "0x0d";
                },
                BsnBesuNonceCoordinatorTest::accepted
        );

        assertThat(selectedNonce).hasValue(BigInteger.valueOf(31));
    }

    /**
     * 验证不同 signer 的广播临界区可并行进入，不受全局锁串行化。
     */
    @Test
    void shouldCoordinateDifferentSignersInParallel() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator coordinator = new BsnBesuNonceCoordinator(stateStore);
        CyclicBarrier broadcastBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EthSendTransaction> first = executor.submit(() -> coordinator.send(
                    SIGNER,
                    () -> BigInteger.ZERO,
                    nonce -> "0x0e",
                    raw -> awaitAndAccept(broadcastBarrier, raw)
            ));
            Future<EthSendTransaction> second = executor.submit(() -> coordinator.send(
                    OTHER_SIGNER,
                    () -> BigInteger.ZERO,
                    nonce -> "0x0f",
                    raw -> awaitAndAccept(broadcastBarrier, raw)
            ));

            assertThat(first.get(5, TimeUnit.SECONDS).getTransactionHash()).isNotBlank();
            assertThat(second.get(5, TimeUnit.SECONDS).getTransactionHash()).isNotBlank();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 验证新 coordinator 进程视图会恢复 UNKNOWN 高水位并阻止 nonce 重用。
     */
    @Test
    void shouldRestoreUnknownStateAcrossCoordinatorRestart() throws Exception {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BsnBesuNonceCoordinator firstCoordinator = new BsnBesuNonceCoordinator(stateStore);
        assertThatThrownBy(() -> firstCoordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(40),
                nonce -> "0x10",
                raw -> {
                    throw new IOException("timeout");
                }
        )).isInstanceOf(IOException.class);

        BsnBesuNonceCoordinator restartedCoordinator = new BsnBesuNonceCoordinator(stateStore);
        assertThatThrownBy(() -> restartedCoordinator.send(
                SIGNER,
                () -> BigInteger.valueOf(40),
                nonce -> "0x11",
                BsnBesuNonceCoordinatorTest::accepted
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved broadcast");
    }

    /**
     * 验证 ACCEPTED 状态必须保存完全一致的本地与远端交易哈希。
     */
    @Test
    void shouldRejectAcceptedStateWithMismatchedHashes() {
        assertThatThrownBy(() -> new BsnBesuNonceStateStore.PersistedState(
                BigInteger.valueOf(51),
                BigInteger.valueOf(50),
                BsnBesuNonceStateStore.Outcome.ACCEPTED,
                "0x" + "a".repeat(64),
                "0x" + "b".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent with outcome ACCEPTED");
    }

    /**
     * 在 barrier 中等待另一个 signer 后返回与本地 payload 匹配的成功响应。
     */
    private static EthSendTransaction awaitAndAccept(CyclicBarrier barrier, String raw)
            throws Exception {
        barrier.await(3, TimeUnit.SECONDS);
        return accepted(raw);
    }

    /**
     * 创建交易哈希与本地签名 payload 一致的成功响应。
     */
    private static EthSendTransaction accepted(String signedRawTransaction) {
        return responseWithHash(Hash.sha3(signedRawTransaction));
    }

    /**
     * 创建指定交易哈希的 Web3j 广播响应。
     */
    private static EthSendTransaction responseWithHash(String transactionHash) {
        EthSendTransaction response = new EthSendTransaction();
        response.setResult(transactionHash);
        return response;
    }

    /**
     * 为 coordinator 状态机测试提供线程安全的 durable store 替身。
     */
    private static final class InMemoryStateStore implements BsnBesuNonceStateStore {
        private final Map<String, PersistedState> states = new ConcurrentHashMap<>();

        /**
         * 按规范化 signer 读取测试状态。
         */
        @Override
        public PersistedState load(String canonicalSigner) {
            return states.get(canonicalSigner.toLowerCase());
        }

        /**
         * 按规范化 signer 保存测试状态。
         */
        @Override
        public void save(String canonicalSigner, PersistedState state) {
            states.put(canonicalSigner.toLowerCase(), state);
        }
    }
}
