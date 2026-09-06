package cn.flying.dao.entity.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import java.util.Date;

/** Allowlisted global member metadata projection without account secrets. */
@Getter
@Setter
@Accessors(chain = true)
public class PlatformUserRow {
    private Long id;
    private Long tenantId;
    private String username;
    private String nickname;
    private String role;
    private Integer status;
    private Date registerTime;
    private Date lastLoginTime;
}
