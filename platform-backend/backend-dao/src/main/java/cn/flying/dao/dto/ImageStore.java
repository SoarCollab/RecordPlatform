package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 图片存储实体类
 * @author: flyingcoding
 * @create: 2025-01-16 13:06
 */
@Data
@TableName("image_store")
@AllArgsConstructor
public class ImageStore {
    @TableId
    String uid;
    String name;
    Date time;

}
