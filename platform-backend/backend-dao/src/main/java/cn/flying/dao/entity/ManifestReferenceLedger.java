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
 * One known object reference or conservative unknown-reference hold in a census.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("manifest_reference_ledger")
public class ManifestReferenceLedger implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long censusId;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long pathTenantId;

    private String storagePath;

    private String cipherHash;

    private String objectIdentityDigest;

    private String sourceType;

    private String sourceId;

    private String sourceKeyDigest;

    private String holdReason;

    private Integer knownReference;

    private Date observedAt;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
