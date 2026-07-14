package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * Merkle attestation batch persisted for exportable proof generation.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("attestation_batch")
public class AttestationBatch implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String batchNo;

    private String idempotencyKey;

    private String merkleRoot;

    private String proofAlgorithm;

    private Integer leafCount;

    private String status;

    private String chainTransactionHash;

    private String chainFileHash;

    private String chainError;

    private Integer attemptCount;

    private Date nextAttemptAt;

    private String claimToken;

    private Date leaseExpiresAt;

    private String confirmationSource;

    private String contractRegistryFingerprint;

    private String contractRegistryJson;

    private String chainType;

    private String chainId;

    private String chainGroupId;

    private String contractName;

    private String contractVersion;

    private String contractAddress;

    private String contractAbiSha256;

    private String contractArtifactBytecodeSha256;

    private String contractCodeSha256;

    private String contractStatus;

    private Long stateVersion;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
