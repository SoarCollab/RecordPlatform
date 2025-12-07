package cn.flying.platformapi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 区块链存储文件请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreFileRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上传者标识
     */
    private String uploader;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 自定义参数（JSON 格式）
     */
    private String param;

    /**
     * 文件内容（分片路径的 JSON）
     */
    private String content;
}
