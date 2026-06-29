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
 * Persisted chunk manifest header for one file version.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("file_chunk_manifest")
public class FileChunkManifest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private Integer fileVersion;

    private String fileHash;

    private String schemaId;

    private String manifestHash;

    private String hashAlgorithm;

    private Long chunkSize;

    private Integer chunkCount;

    private Long totalSize;

    private String merkleRoot;

    private String encryptionAlgorithm;

    private String storageBackend;

    private String manifestJson;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
