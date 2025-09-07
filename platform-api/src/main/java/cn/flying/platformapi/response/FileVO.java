package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件基本信息视图对象
 * 用于返回文件的基本信息，包含文件名和文件哈希
 * 主要用于文件列表展示场景
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件名称
     * 用户上传时的原始文件名
     */
    private String fileName;
    
    /**
     * 文件哈希值
     * 文件内容的唯一标识，用于文件完整性验证和去重
     */
    private String fileHash;
}
