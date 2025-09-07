package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 图片存储实体类
 * @author: 王贝强
 * @create: 2025-01-16 13:06
 */
@Data
@TableName("image_store")
@AllArgsConstructor
@Schema(name = "image_store", description = "图片存储实体类")
public class ImageStore {
    @TableId
    @Schema(description = "主键ID")
    String uid;

    @Schema(description = "图片url")
    String name;

    @Schema(description = "图片上传日期")
    Date time;

}
