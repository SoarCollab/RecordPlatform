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
 * 已签发证明包的不可变签名快照与可变在线状态。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("proof_bundle_issuance")
public class ProofBundleIssuance implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private String proofId;

    private Long fileId;

    private Integer fileVersion;

    private Long leafId;

    private String manifestHash;

    private String manifestJson;

    private String signatureJws;

    private String signatureAlgorithm;

    private String keyId;

    private Integer keyVersion;

    private String publicKeySpki;

    private String publicKeyFingerprint;

    private String issuedStatus;

    private String status;

    private Long statusVersion;

    private String statusReason;

    private Date issuedAt;

    private Date revokedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
