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
 * Persisted wrapped file data-key envelope.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("file_key_envelope")
public class FileKeyEnvelope implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private String fileHash;

    private String recipientType;

    private Long recipientId;

    private Integer keyVersion;

    private String algorithmSuite;

    private String encryptionAlgorithm;

    private String wrappingAlgorithm;

    private String kmsProvider;

    private String kmsKeyId;

    private String encryptedDataKey;

    private String wrappingIv;

    private String aadHash;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
