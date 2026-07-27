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
 * Tenant-scoped runtime crypto policy whose version is frozen into operational decisions.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("tenant_crypto_policy")
public class TenantCryptoPolicy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String contentEncryptionSuite;
    private String envelopeSignatureSuite;
    private String kemSuite;
    private String proofSuite;
    private String wrappingProvider;
    private Integer wrappingProviderContract;
    private String signedProofSignatureSuite;
    private String signedProofSuite;
    private String signingProvider;
    private Integer signingProviderContract;
    private Long policyVersion;
    private Long createdBy;
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
