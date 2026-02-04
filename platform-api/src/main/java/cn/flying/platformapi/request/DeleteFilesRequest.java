package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 删除文件请求 DTO
 */
public record DeleteFilesRequest(
        String uploader,
        List<String> fileHashList
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
