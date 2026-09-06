package cn.flying.dao.entity.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import cn.flying.dao.entity.Tenant;

/** Allowlisted global tenant metadata projection. */
@Getter
@Setter
@Accessors(chain = true)
public class PlatformTenantRow extends Tenant {
    private Long memberCount;
}
