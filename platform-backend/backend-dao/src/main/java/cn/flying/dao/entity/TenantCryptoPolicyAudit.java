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
 * Sanitized immutable evidence for tenant crypto policy changes.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("tenant_crypto_policy_audit")
public class TenantCryptoPolicyAudit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long policyId;
    private Long policyVersion;
    private Long actorId;
    private String action;
    private String outcome;
    private String oldPolicyFingerprint;
    private String newPolicyFingerprint;
    private String failureReason;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableLogic
    private Integer deleted;
}
