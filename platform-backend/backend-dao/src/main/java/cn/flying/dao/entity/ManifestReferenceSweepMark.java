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
 * Durable mark/grace/delete state for one reference-censused storage object.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("manifest_reference_sweep_mark")
public class ManifestReferenceSweepMark implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long pathTenantId;

    private String storagePath;

    private String cipherHash;

    private Long contentLength;

    private String etag;

    private String objectIdentityDigest;

    private Long markCensusId;

    private String status;

    private Date protectionUntil;

    private String claimToken;

    private Date leaseExpiresAt;

    private Integer attemptCount;

    private Date nextRetryAt;

    private String reasonCode;

    private String lastErrorClass;

    private Date deletedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
