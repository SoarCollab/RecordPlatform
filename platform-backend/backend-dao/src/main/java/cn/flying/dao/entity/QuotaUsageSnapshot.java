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
 * 配额使用量快照实体。
 * userId = 0 表示租户聚合快照。
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("quota_usage_snapshot")
public class QuotaUsageSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;

    private Long userId;

    private Long usedStorageBytes;

    private Long usedFileCount;

    private String source;

    @TableField(fill = FieldFill.INSERT)
    private Date snapshotTime;
}
