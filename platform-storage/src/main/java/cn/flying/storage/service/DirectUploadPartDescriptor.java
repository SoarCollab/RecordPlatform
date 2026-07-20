package cn.flying.storage.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * storage 根据当前租户和后端可信上传计划重建的单个直传分片描述。
 *
 * <p>该内部合同只携带元数据，不允许携带完整分片字节。</p>
 */
record DirectUploadPartDescriptor(
        Long tenantId,
        String sessionId,
        int partIndex,
        String sourceNode,
        String stagingObjectName,
        String finalObjectName,
        long size,
        String eTag,
        String plainHash,
        String cipherHash,
        String checksumAlgorithm,
        List<String> targetNodes,
        int requiredQuorum
) {

    /**
     * 固化目标节点快照，避免提升期间拓扑集合被调用方修改。
     */
    DirectUploadPartDescriptor {
        targetNodes = List.copyOf(targetNodes);
    }

    /**
     * 返回只包含 staging 身份的描述，供 abort 和生命周期清理复用同一锁边界。
     *
     * @return staging 对象描述
     */
    DirectUploadStagingDescriptor stagingDescriptor() {
        return new DirectUploadStagingDescriptor(
                tenantId,
                sessionId,
                partIndex,
                sourceNode,
                stagingObjectName
        );
    }
}

/**
 * 一个受 tenant/session/part 约束的 staging 对象描述。
 */
record DirectUploadStagingDescriptor(
        Long tenantId,
        String sessionId,
        int partIndex,
        String nodeName,
        String objectName
) {

    /**
     * 返回同一 lifecycle 下仅供 storage 凭据访问的确定性 sealed key。
     *
     * @return public staging key 对应的 sealed key
     */
    String sealedObjectName() {
        return objectName + ".sealed";
    }
}

/**
 * 单分片提升完成后可公开给 manifest 编排层的可信结果。
 */
record DirectUploadPromotionResult(
        long size,
        String eTag
) {
}

/**
 * 允许单分片校验在临时 digest 上运行，并仅在成功后提交整文件 SHA-256 状态。
 */
final class DirectUploadDigestAccumulator {
    private static final String SHA_256 = "SHA-256";

    private MessageDigest committedDigest;

    private DirectUploadDigestAccumulator(MessageDigest committedDigest) {
        this.committedDigest = committedDigest;
    }

    /**
     * 创建空的整文件 SHA-256 累加器。
     *
     * @return 新累加器
     */
    static DirectUploadDigestAccumulator sha256() {
        try {
            return new DirectUploadDigestAccumulator(MessageDigest.getInstance(SHA_256));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 克隆当前已提交状态，失败候选不会污染后续 replica 重试。
     *
     * @return 可独立更新的候选 digest
     */
    MessageDigest fork() {
        try {
            return (MessageDigest) committedDigest.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException("SHA-256 provider does not support safe digest snapshots", e);
        }
    }

    /**
     * 在分片大小和 SHA-256 全部验证成功后提交候选状态。
     *
     * @param candidate 已验证候选 digest
     */
    void commit(MessageDigest candidate) {
        committedDigest = candidate;
    }

    /**
     * 完成整文件摘要并返回规范内容哈希。
     *
     * @return `sha256:` 前缀内容哈希
     */
    String finishHash() {
        return "sha256:" + java.util.HexFormat.of().formatHex(committedDigest.digest());
    }
}
