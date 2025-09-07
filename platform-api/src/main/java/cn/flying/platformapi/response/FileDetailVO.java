package cn.flying.platformapi.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件详情视图对象
 * 用于返回文件的详细信息，包括上传者、文件名、参数、内容、文件哈希和上传时间
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FileDetailVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文件上传者
     */
    private String uploader;
    
    /**
     * 文件名称
     */
    private String fileName;
    
    /**
     * 文件参数（元数据）
     */
    private String param;
    
    /**
     * 文件内容
     */
    private String content;
    
    /**
     * 文件哈希值
     * 用于唯一标识文件，确保文件完整性
     */
    private String fileHash;
    
    /**
     * 文件上传时间
     */
    private String uploadTime;
}