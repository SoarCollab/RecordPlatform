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
 * Storage integrity check alert entity.
 * Records detected data corruption or tampering incidents.
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("integrity_alert")
public class IntegrityAlert implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long fileId;

    private String fileHash;

    private String actualHash;

    private String chainHash;

    private String alertType;

    private String severity;

    private String evidence;

    private Integer status;

    private Long resolvedBy;

    private Date resolvedAt;

    private String note;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;

    /**
     * Alert type constants.
     */
    @Getter
    public enum AlertType {
        HASH_MISMATCH(AlertSeverity.CRITICAL),
        FILE_NOT_FOUND(AlertSeverity.CRITICAL),
        CHAIN_NOT_FOUND(AlertSeverity.ERROR),
        MANIFEST_MISSING(AlertSeverity.WARNING),
        MANIFEST_INVALID(AlertSeverity.CRITICAL),
        OBJECT_NOT_FOUND(AlertSeverity.CRITICAL),
        METADATA_MISMATCH(AlertSeverity.CRITICAL),
        CONTENT_HASH_MISMATCH(AlertSeverity.CRITICAL),
        CHAIN_MISMATCH(AlertSeverity.CRITICAL);

        private final AlertSeverity defaultSeverity;

        AlertType(AlertSeverity defaultSeverity) {
            this.defaultSeverity = defaultSeverity;
        }
    }

    /**
     * Stable operational severity values persisted with integrity alerts.
     */
    public enum AlertSeverity {
        WARNING,
        ERROR,
        CRITICAL
    }

    /**
     * Alert status constants.
     */
    @Getter
    public enum AlertStatus {
        PENDING(0),
        ACKNOWLEDGED(1),
        RESOLVED(2);

        private final int code;

        AlertStatus(int code) {
            this.code = code;
        }
    }
}
