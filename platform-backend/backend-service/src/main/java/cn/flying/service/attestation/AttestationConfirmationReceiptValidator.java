package cn.flying.service.attestation;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一校验批量存证确认来源和交易哈希的证据矩阵。
 */
public final class AttestationConfirmationReceiptValidator {

    public static final String SOURCE_CHAIN_WRITE = "CHAIN_WRITE";
    public static final String SOURCE_CHAIN_QUERY_BEFORE_WRITE = "CHAIN_QUERY_BEFORE_WRITE";
    public static final String SOURCE_CHAIN_QUERY_AFTER_WRITE = "CHAIN_QUERY_AFTER_WRITE";

    private static final Set<String> CHAIN_QUERY_SOURCES = Set.of(
            SOURCE_CHAIN_QUERY_BEFORE_WRITE,
            SOURCE_CHAIN_QUERY_AFTER_WRITE);
    private static final Pattern CHAIN_ROOT_HASH_PATTERN = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final Pattern CHAIN_TRANSACTION_HASH_PATTERN =
            Pattern.compile("(?i)(?:0x)?[0-9a-f]{64}");

    private AttestationConfirmationReceiptValidator() {
    }

    /**
     * 判断持久化确认来源与交易哈希是否构成可信链回执。
     *
     * @param confirmationSource 确认来源
     * @param transactionHash 链写交易哈希；查询命中时必须为空
     * @param chainRoot 链上确认的 32-byte Merkle 根
     * @return 来源和交易哈希语义一致时返回 true
     */
    public static boolean isValid(
            String confirmationSource,
            String transactionHash,
            String chainRoot
    ) {
        String normalizedSource = confirmationSource == null ? "" : confirmationSource;
        String normalizedRoot = chainRoot == null ? "" : chainRoot.trim();
        if (!CHAIN_ROOT_HASH_PATTERN.matcher(normalizedRoot).matches()) {
            return false;
        }
        String normalizedHash = transactionHash == null ? "" : transactionHash.trim();
        if (SOURCE_CHAIN_WRITE.equals(normalizedSource)) {
            return CHAIN_TRANSACTION_HASH_PATTERN.matcher(normalizedHash).matches();
        }
        return CHAIN_QUERY_SOURCES.contains(normalizedSource) && normalizedHash.isEmpty();
    }

    /**
     * 强制校验持久化确认来源，供状态迁移入口在数据库写入前失败关闭。
     *
     * @param confirmationSource 确认来源
     * @param transactionHash 链写交易哈希；查询命中时必须为空
     * @param chainRoot 链上确认的 32-byte Merkle 根
     */
    public static void requireValid(
            String confirmationSource,
            String transactionHash,
            String chainRoot
    ) {
        if (!isValid(confirmationSource, transactionHash, chainRoot)) {
            throw new IllegalArgumentException(
                    "Attestation confirmation source, transaction hash, or chain root is invalid");
        }
    }
}
