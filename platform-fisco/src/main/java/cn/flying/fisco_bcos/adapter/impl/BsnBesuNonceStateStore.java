package cn.flying.fisco_bcos.adapter.impl;

import java.io.IOException;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * 持久化 BSN Besu signer 的 nonce 高水位和最近一次广播状态。
 */
public interface BsnBesuNonceStateStore {

    /**
     * 读取 signer 已持久化的 nonce 状态；首次启动时返回 {@code null}。
     */
    PersistedState load(String canonicalSigner) throws IOException;

    /**
     * 原子持久化 signer 的最新 nonce 状态。
     */
    void save(String canonicalSigner, PersistedState state) throws IOException;

    /**
     * 广播阶段的持久化状态。
     */
    enum Outcome {
        LOCAL_FAILURE,
        BROADCASTING,
        ACCEPTED,
        DEFINITE_REJECT,
        UNKNOWN
    }

    /**
     * signer 的 durable nonce 快照；nextNonce 是后续分配不得低于的本地高水位。
     */
    record PersistedState(
            BigInteger nextNonce,
            BigInteger lastNonce,
            Outcome outcome,
            String localTransactionHash,
            String remoteTransactionHash
    ) {
        private static final Pattern TRANSACTION_HASH_PATTERN =
                Pattern.compile("^0x[0-9a-f]{64}$");

        /**
         * 校验快照字段和状态转换关系，拒绝会降低 nonce 安全性的语义损坏状态。
         */
        public PersistedState {
            if (nextNonce == null || nextNonce.signum() < 0) {
                throw new IllegalArgumentException("nextNonce must be non-negative");
            }
            if (lastNonce == null || lastNonce.signum() < 0) {
                throw new IllegalArgumentException("lastNonce must be non-negative");
            }
            if (outcome == null) {
                throw new IllegalArgumentException("outcome is required");
            }
            BigInteger expectedNextNonce = switch (outcome) {
                case LOCAL_FAILURE, DEFINITE_REJECT -> lastNonce;
                case BROADCASTING, ACCEPTED, UNKNOWN -> lastNonce.add(BigInteger.ONE);
            };
            if (!expectedNextNonce.equals(nextNonce)) {
                throw new IllegalArgumentException("nextNonce is inconsistent with outcome");
            }
            requireValidTransactionHash(localTransactionHash, "localTransactionHash");
            requireValidTransactionHash(remoteTransactionHash, "remoteTransactionHash");
            switch (outcome) {
                case LOCAL_FAILURE -> requireHashPresence(
                        localTransactionHash == null && remoteTransactionHash == null,
                        outcome
                );
                case BROADCASTING -> requireHashPresence(
                        localTransactionHash != null && remoteTransactionHash == null,
                        outcome
                );
                case ACCEPTED -> requireHashPresence(
                        localTransactionHash != null
                                && remoteTransactionHash != null
                                && localTransactionHash.equalsIgnoreCase(remoteTransactionHash),
                        outcome
                );
                case DEFINITE_REJECT -> requireHashPresence(
                        localTransactionHash != null && remoteTransactionHash == null,
                        outcome
                );
                case UNKNOWN -> requireHashPresence(localTransactionHash != null, outcome);
            }
        }

        /**
         * 判断该快照是否仍包含无法证明已接受或已拒绝的广播。
         */
        public boolean isUnresolvedBroadcast() {
            return outcome == Outcome.BROADCASTING || outcome == Outcome.UNKNOWN;
        }

        /**
         * 校验可选交易哈希为规范化 32 字节小写十六进制值。
         */
        private static void requireValidTransactionHash(String hash, String field) {
            if (hash != null && !TRANSACTION_HASH_PATTERN.matcher(hash).matches()) {
                throw new IllegalArgumentException(field + " is invalid");
            }
        }

        /**
         * 校验不同广播结果允许的本地与远端哈希组合。
         */
        private static void requireHashPresence(boolean valid, Outcome outcome) {
            if (!valid) {
                throw new IllegalArgumentException(
                        "transaction hashes are inconsistent with outcome " + outcome
                );
            }
        }
    }
}
