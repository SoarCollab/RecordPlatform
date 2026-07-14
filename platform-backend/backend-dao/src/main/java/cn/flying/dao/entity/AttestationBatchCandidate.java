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
 * 记录一个文件版本进入生产 Merkle batch 前的持久化候选状态。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("attestation_batch_candidate")
public class AttestationBatchCandidate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private Integer fileVersion;

    private Long manifestId;

    private String evidenceType;

    private String evidenceHash;

    private String chainRecordId;

    private String status;

    private Long batchId;

    private String claimToken;

    private Date leaseExpiresAt;

    private Integer attemptCount;

    private String lastError;

    private Date eligibleAt;

    private Date batchedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private Integer activeManifestCount;
}
