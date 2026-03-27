package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
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

    private Integer status;

    private Long resolvedBy;

    private Date resolvedAt;

    private String note;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * Alert type constants.
     */
    public enum AlertType {
        HASH_MISMATCH,
        FILE_NOT_FOUND,
        CHAIN_NOT_FOUND;
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
