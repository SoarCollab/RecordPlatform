package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 文件信息实体类
 * @author flyingcoding
 * @create: 2025-03-13 00:25
 */
@Setter
@Getter
@TableName("file")
@Accessors(chain = true)
@Schema(name = "file", description = "文件信息实体类")
public class File implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户ID")
    private Long uid;

    /**
     * 来源文件Id(标识分享文件原始来源)
     */
    @Schema(description = "来源Id(标识分享文件原始来源用户Id)")
    private Long origin;

    @Schema(description = "文件名称")
    private String fileName;

    /**
     * 文件分类
     */
    @Schema(description = "文件分类")
    private String classification;

    /**
     * 文件参数(JSON):文件类型、文件描述、文件大小等其它参数
     */
    @Schema(description = "文件参数(JSON):文件类型、文件描述、文件大小等其它参数")
    private String fileParam;

    @Schema(description = "文件哈希")
    private String fileHash;

    @Schema(description = "交易哈希")
    private String transactionHash;

    /**
     * 文件上传状态(是否成功上传至区块链与分布式存储): 见——>FileUploadStatus
     */
    @Schema(description = "文件上传状态(是否成功上传至区块链与分布式存储): 见枚举类——>FileUploadStatus")
    private Integer status;

    @TableLogic
    @Schema(description = "逻辑删除字段：0—>未删除 , 1->已删除")
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(exist = false)
    @Schema(description = "文件大小")
    private Long fileSize;

    @TableField(exist = false)
    @Schema(description = "文件类型")
    private String contentType;

    public Long getFileSize() {
        if (fileSize == null && fileParam != null) {
            try {
                java.util.Map map = cn.flying.common.util.JsonConverter.parse(fileParam, java.util.Map.class);
                if (map != null && map.containsKey("fileSize")) {
                    Object val = map.get("fileSize");
                    if (val instanceof Number) {
                        this.fileSize = ((Number) val).longValue();
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return fileSize;
    }

    public String getContentType() {
        if (contentType == null && fileParam != null) {
            try {
                java.util.Map map = cn.flying.common.util.JsonConverter.parse(fileParam, java.util.Map.class);
                if (map != null && map.containsKey("contentType")) {
                    this.contentType = (String) map.get("contentType");
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return contentType;
    }
}
