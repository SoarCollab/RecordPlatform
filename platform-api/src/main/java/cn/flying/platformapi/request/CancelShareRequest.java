package cn.flying.platformapi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 取消分享请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelShareRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分享码
     */
    private String shareCode;

    /**
     * 上传者标识（用于权限校验）
     */
    private String uploader;
}
