package cn.flying.service.proof.signed;

import cn.flying.dao.vo.file.ProofSigningKeyVO;
import cn.flying.dao.vo.file.ProofStatusVO;

/**
 * 签名 proof ZIP 导出、生命周期状态和历史公钥查询边界。
 */
public interface SignedProofArchiveService {

    /**
     * 按文件版本导出签名 proof ZIP。
     */
    ProofArchive exportByFileId(Long userId, Long fileId);

    /**
     * 按 attestation leaf 导出签名 proof ZIP。
     */
    ProofArchive exportByLeafId(Long userId, Long leafId);

    /**
     * 撤销当前用户有权管理的 leaf proof。
     */
    ProofStatusVO revokeByLeafId(Long userId, Long leafId, String reason);

    /**
     * 无需租户上下文读取不可枚举 proofId 的公开状态。
     */
    ProofStatusVO getPublicStatus(String proofId);

    /**
     * 无需租户上下文读取历史签名公钥。
     */
    ProofSigningKeyVO getPublicSigningKey(String keyId, Integer keyVersion);
}
