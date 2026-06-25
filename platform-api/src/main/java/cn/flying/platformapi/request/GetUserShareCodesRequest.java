package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;

/**
 * 获取用户分享码请求 DTO。
 */
public record GetUserShareCodesRequest(
        String uploader,
        String requester
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
