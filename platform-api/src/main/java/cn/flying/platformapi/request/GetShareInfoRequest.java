package cn.flying.platformapi.request;

import java.io.Serial;
import java.io.Serializable;

/**
 * 获取分享详情请求 DTO。
 */
public record GetShareInfoRequest(
        String shareCode,
        String requester
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
