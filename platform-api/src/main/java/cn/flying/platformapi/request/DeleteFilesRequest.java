package cn.flying.platformapi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 删除文件请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteFilesRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上传者标识
     */
    private String uploader;

    /**
     * 待删除的文件哈希列表
     */
    private List<String> fileHashList;
}
