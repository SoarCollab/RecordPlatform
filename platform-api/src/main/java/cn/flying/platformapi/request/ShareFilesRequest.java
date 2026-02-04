package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分享文件请求 DTO
 */
public record ShareFilesRequest(
        String uploader,
        List<String> fileHashList,
        Integer expireMinutes
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
