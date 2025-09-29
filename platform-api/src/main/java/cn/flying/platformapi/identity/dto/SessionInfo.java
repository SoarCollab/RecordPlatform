package cn.flying.platformapi.identity.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

@Data
public class SessionInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String sessionId;
    private Long userId;
    private String clientIp;
    private String userAgent;
    private Date loginTime;
    private Date lastAccessTime;
    private Date expireTime;
    private Integer status;
}
