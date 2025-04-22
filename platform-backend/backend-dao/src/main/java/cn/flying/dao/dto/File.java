package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 文件信息实体类
 * @author: flyingcoding
 * @create: 2025-03-13 00:25
 */
@Setter
@Getter
@TableName("file")
@Accessors(chain = true)
public class File implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String uid;

    /**
     * 来源文件Id(标识分享文件原始来源)
     */
    private Long Origin;

    private String fileName;

    /**
     * 文件分类
     */
    private String classification;

    /**
     * 文件参数(JSON):文件类型、文件描述、文件大小等其它参数
     */
    private String fileParam;

    private String fileHash;

    /**
     * 文件上传状态(是否成功上传至区块链与分布式存储): 见——>FileUploadStatus
     */
    private Integer status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

}
