package cn.flying.fisco_bcos.adapter.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthSendTransaction;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 按签名地址协调 BSN Besu 原始交易 nonce。
 *
 * <p>同一 signer 的临界区覆盖 PENDING 同步、本地构造/签名、广播结果分类和状态推进，
 * 但不覆盖交易回执确认；不同 signer 使用独立锁并行发送。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "blockchain.active", havingValue = "bsn-besu")
public final class BsnBesuNonceCoordinator {

    private static final Pattern SIGNER_PATTERN = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TRANSACTION_HASH_PATTERN =
            Pattern.compile("^0x[0-9a-fA-F]{64}$");
    private static final Set<Integer> DEFINITE_JSON_RPC_REJECT_CODES =
            Set.of(-32700, -32600, -32601, -32602);
    private static final List<String> DEFINITE_BESU_REJECT_MESSAGES = List.of(
            "insufficient funds",
            "up-front cost",
            "intrinsic gas",
            "gasprice is less than the current basefee",
            "max priority fee per gas cannot be greater",
            "transaction gas limit must be",
            "transaction was meant for chain id",
            "sender could not be extracted",
            "invalid signature",
            "is invalid, accepted transaction types",
            "transaction type not supported",
            "nonce must be less than",
            "initcode size"
    );

    private final ConcurrentMap<String, SignerState> signerStates = new ConcurrentHashMap<>();
    private final BsnBesuNonceStateStore stateStore;

    /**
     * 创建使用指定 durable state store 的 nonce coordinator。
     */
    public BsnBesuNonceCoordinator(BsnBesuNonceStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 为指定 signer 安全选择 nonce、构造签名交易并完成一次同步广播。
     *
     * @param signer 签名地址
     * @param pendingNonceSupplier 节点 PENDING nonce 查询器
     * @param transactionFactory 仅执行本地构造和签名的工厂
     * @param broadcaster 已签名原始交易广播器
     * @return Web3j 广播响应
     * @throws Exception 查询、本地构造或广播失败时抛出原始异常
     */
    public EthSendTransaction send(
            String signer,
            PendingNonceSupplier pendingNonceSupplier,
            SignedRawTransactionFactory transactionFactory,
            RawTransactionBroadcaster broadcaster
    ) throws Exception {
        String canonicalSigner = canonicalizeSigner(signer);
        SignerState state = signerStates.computeIfAbsent(
                canonicalSigner,
                ignored -> new SignerState()
        );

        lockInterruptibly(state.lock);
        try {
            initializeState(canonicalSigner, state);
            BigInteger nodePending = requireValidNonce(pendingNonceSupplier.get());
            requireResolvedBroadcast(canonicalSigner, state, nodePending);
            BigInteger nonce = state.nextNonce == null
                    ? nodePending
                    : nodePending.max(state.nextNonce);

            String signedRawTransaction;
            String localTransactionHash;
            try {
                signedRawTransaction = requireSignedRawTransaction(transactionFactory.build(nonce));
                localTransactionHash = Hash.sha3(signedRawTransaction);
            } catch (Exception exception) {
                persistLocalFailure(canonicalSigner, state, nonce, exception);
                log.warn(
                        "[BSN Besu nonce] 本地构造或签名失败，保留 nonce 供重同步, signer={}, nonce={}",
                        canonicalSigner,
                        nonce
                );
                throw exception;
            }

            persistState(
                    canonicalSigner,
                    state,
                    new BsnBesuNonceStateStore.PersistedState(
                            nonce.add(BigInteger.ONE),
                            nonce,
                            BsnBesuNonceStateStore.Outcome.BROADCASTING,
                            localTransactionHash,
                            null
                    )
            );

            EthSendTransaction response;
            try {
                response = broadcaster.broadcast(signedRawTransaction);
            } catch (Exception exception) {
                persistUnknownAfterBroadcastFailure(
                        canonicalSigner,
                        state,
                        nonce,
                        localTransactionHash,
                        exception
                );
                log.warn(
                        "[BSN Besu nonce] 广播结果不确定，禁止回退 nonce, signer={}, nonce={}, cause={}",
                        canonicalSigner,
                        nonce,
                        exception.getClass().getSimpleName()
                );
                throw exception;
            }

            BroadcastOutcome outcome = classify(response, localTransactionHash);
            String remoteTransactionHash = response == null
                    ? null
                    : normalizeOptionalTransactionHash(response.getTransactionHash());
            if (outcome == BroadcastOutcome.DEFINITE_REJECT) {
                persistState(
                        canonicalSigner,
                        state,
                        new BsnBesuNonceStateStore.PersistedState(
                                nonce,
                                nonce,
                                BsnBesuNonceStateStore.Outcome.DEFINITE_REJECT,
                                localTransactionHash,
                                remoteTransactionHash
                        )
                );
                log.warn(
                        "[BSN Besu nonce] 节点明确拒绝交易，下一请求将从 PENDING 重同步, signer={}, nonce={}, code={}",
                        canonicalSigner,
                        nonce,
                        response.getError().getCode()
                );
            } else {
                if (outcome == BroadcastOutcome.UNKNOWN) {
                    persistState(
                            canonicalSigner,
                            state,
                            new BsnBesuNonceStateStore.PersistedState(
                                    nonce.add(BigInteger.ONE),
                                    nonce,
                                    BsnBesuNonceStateStore.Outcome.UNKNOWN,
                                    localTransactionHash,
                                    remoteTransactionHash
                            )
                    );
                    log.warn(
                            "[BSN Besu nonce] 广播响应无法证明交易未进入 mempool，禁止回退 nonce, signer={}, nonce={}",
                            canonicalSigner,
                            nonce
                    );
                } else {
                    persistState(
                            canonicalSigner,
                            state,
                            new BsnBesuNonceStateStore.PersistedState(
                                    nonce.add(BigInteger.ONE),
                                    nonce,
                                    BsnBesuNonceStateStore.Outcome.ACCEPTED,
                                    localTransactionHash,
                                    remoteTransactionHash
                            )
                    );
                    log.debug(
                            "[BSN Besu nonce] 交易已接受, signer={}, nonce={}, txHash={}",
                            canonicalSigner,
                            nonce,
                            response.getTransactionHash()
                    );
                }
            }

            if (response == null || (!response.hasError()
                    && outcome != BroadcastOutcome.ACCEPTED)) {
                throw new IOException("BSN Besu returned an empty or invalid transaction response");
            }
            return response;
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * 首次使用 signer 时从 durable state 恢复本地 nonce 高水位和未决广播。
     */
    private void initializeState(String canonicalSigner, SignerState state) throws IOException {
        if (state.initialized) {
            return;
        }
        BsnBesuNonceStateStore.PersistedState persistedState = stateStore.load(canonicalSigner);
        state.persistedState = persistedState;
        state.nextNonce = persistedState == null ? null : persistedState.nextNonce();
        state.initialized = true;
    }

    /**
     * 未决广播只有在节点 PENDING 已越过对应 nonce 时才允许继续分配新 payload。
     */
    private void requireResolvedBroadcast(
            String canonicalSigner,
            SignerState state,
            BigInteger nodePending
    ) {
        BsnBesuNonceStateStore.PersistedState persistedState = state.persistedState;
        if (persistedState == null || !persistedState.isUnresolvedBroadcast()) {
            return;
        }
        BigInteger unresolvedNonce = persistedState.lastNonce();
        if (unresolvedNonce == null || nodePending.compareTo(unresolvedNonce) <= 0) {
            throw new IllegalStateException(
                    "BSN Besu signer has an unresolved broadcast; manual reconciliation is required"
            );
        }
        log.info(
                "[BSN Besu nonce] PENDING 已越过未决 nonce，允许继续发送, signer={}, nonce={}, pending={}",
                canonicalSigner,
                unresolvedNonce,
                nodePending
        );
    }

    /**
     * 以可中断方式获取 signer 锁，并在中断时恢复线程中断标记。
     */
    private void lockInterruptibly(ReentrantLock lock) throws InterruptedException {
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    /**
     * 校验节点返回的 nonce，拒绝空值和负数。
     */
    private BigInteger requireValidNonce(BigInteger nonce) {
        if (nonce == null || nonce.signum() < 0) {
            throw new IllegalStateException("BSN Besu returned an invalid PENDING nonce");
        }
        return nonce;
    }

    /**
     * 校验本地签名结果，避免把空 payload 误判为广播失败。
     */
    private String requireSignedRawTransaction(String signedRawTransaction) {
        if (signedRawTransaction == null || signedRawTransaction.isBlank()) {
            throw new IllegalStateException("BSN Besu signed raw transaction is empty");
        }
        return signedRawTransaction;
    }

    /**
     * 根据同步 RPC 响应保守区分成功、明确拒绝和不确定结果。
     */
    private BroadcastOutcome classify(
            EthSendTransaction response,
            String localTransactionHash
    ) {
        if (response == null) {
            return BroadcastOutcome.UNKNOWN;
        }
        if (!response.hasError()) {
            return isMatchingTransactionHash(
                    response.getTransactionHash(),
                    localTransactionHash
            )
                    ? BroadcastOutcome.ACCEPTED
                    : BroadcastOutcome.UNKNOWN;
        }
        if (response.getResult() != null) {
            return BroadcastOutcome.UNKNOWN;
        }
        return isDefiniteReject(response.getError())
                ? BroadcastOutcome.DEFINITE_REJECT
                : BroadcastOutcome.UNKNOWN;
    }

    /**
     * 持久化本地确定失败；持久化异常作为 suppressed 信息附加到原失败。
     */
    private void persistLocalFailure(
            String canonicalSigner,
            SignerState state,
            BigInteger nonce,
            Exception originalException
    ) {
        try {
            persistState(
                    canonicalSigner,
                    state,
                    new BsnBesuNonceStateStore.PersistedState(
                            nonce,
                            nonce,
                            BsnBesuNonceStateStore.Outcome.LOCAL_FAILURE,
                            null,
                            null
                    )
            );
        } catch (IOException persistenceException) {
            originalException.addSuppressed(persistenceException);
        }
    }

    /**
     * 广播异常后尽力把预写 BROADCASTING 状态提升为 UNKNOWN，保留安全高水位。
     */
    private void persistUnknownAfterBroadcastFailure(
            String canonicalSigner,
            SignerState state,
            BigInteger nonce,
            String localTransactionHash,
            Exception originalException
    ) {
        try {
            persistState(
                    canonicalSigner,
                    state,
                    new BsnBesuNonceStateStore.PersistedState(
                            nonce.add(BigInteger.ONE),
                            nonce,
                            BsnBesuNonceStateStore.Outcome.UNKNOWN,
                            localTransactionHash,
                            null
                    )
            );
        } catch (IOException persistenceException) {
            originalException.addSuppressed(persistenceException);
            log.error(
                    "[BSN Besu nonce] UNKNOWN 状态持久化失败，保留预写 BROADCASTING 状态, signer={}, nonce={}",
                    canonicalSigner,
                    nonce,
                    persistenceException
            );
        }
    }

    /**
     * 原子保存 durable 状态，并仅在落盘成功后更新进程内快照。
     */
    private void persistState(
            String canonicalSigner,
            SignerState state,
            BsnBesuNonceStateStore.PersistedState persistedState
    ) throws IOException {
        stateStore.save(canonicalSigner, persistedState);
        state.persistedState = persistedState;
        state.nextNonce = persistedState.nextNonce();
    }

    /**
     * 仅把标准协议拒绝和明确的 Besu 本地校验错误视为未进入 mempool。
     */
    private boolean isDefiniteReject(Response.Error error) {
        if (DEFINITE_JSON_RPC_REJECT_CODES.contains(error.getCode())) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        return DEFINITE_BESU_REJECT_MESSAGES.stream().anyMatch(normalizedMessage::contains);
    }

    /**
     * 校验节点成功响应中的交易哈希，畸形成功响应按不确定结果处理。
     */
    private boolean isValidTransactionHash(String transactionHash) {
        return transactionHash != null
                && TRANSACTION_HASH_PATTERN.matcher(transactionHash).matches();
    }

    /**
     * 验证节点交易哈希与本地签名 payload 的 Keccak-256 完全一致。
     */
    private boolean isMatchingTransactionHash(
            String remoteTransactionHash,
            String localTransactionHash
    ) {
        return isValidTransactionHash(remoteTransactionHash)
                && remoteTransactionHash.equalsIgnoreCase(localTransactionHash);
    }

    /**
     * 将可选交易哈希规范化为小写；畸形值保留为空以避免写入损坏状态。
     */
    private String normalizeOptionalTransactionHash(String transactionHash) {
        return isValidTransactionHash(transactionHash)
                ? transactionHash.toLowerCase(Locale.ROOT)
                : null;
    }

    /**
     * 将同一 signer 的大小写和 0x 前缀形式归一到同一个状态键。
     */
    private String canonicalizeSigner(String signer) {
        if (signer == null) {
            throw new IllegalArgumentException("BSN Besu signer address is required");
        }
        String normalized = signer.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("0x")) {
            normalized = normalized.substring(2);
        }
        if (!SIGNER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("BSN Besu signer address is invalid");
        }
        return "0x" + normalized;
    }

    /**
     * 查询节点当前 PENDING nonce。
     */
    @FunctionalInterface
    public interface PendingNonceSupplier {
        BigInteger get() throws Exception;
    }

    /**
     * 使用已保留 nonce 在本地构造并签名原始交易。
     */
    @FunctionalInterface
    public interface SignedRawTransactionFactory {
        String build(BigInteger nonce) throws Exception;
    }

    /**
     * 将已签名原始交易提交给 Besu 节点。
     */
    @FunctionalInterface
    public interface RawTransactionBroadcaster {
        EthSendTransaction broadcast(String signedRawTransaction) throws Exception;
    }

    /**
     * 同一 signer 的本地 nonce 高水位和互斥状态。
     */
    private static final class SignerState {
        private final ReentrantLock lock = new ReentrantLock();
        private boolean initialized;
        private BigInteger nextNonce;
        private BsnBesuNonceStateStore.PersistedState persistedState;
    }

    /**
     * 同步广播调用的保守结果分类。
     */
    private enum BroadcastOutcome {
        ACCEPTED,
        DEFINITE_REJECT,
        UNKNOWN
    }
}
