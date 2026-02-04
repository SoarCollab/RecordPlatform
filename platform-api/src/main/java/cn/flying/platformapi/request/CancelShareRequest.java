package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;

/**
 * 取消分享请求 DTO
 */
public record CancelShareRequest(
        String shareCode,
        String uploader
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
