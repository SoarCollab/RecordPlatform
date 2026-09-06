package cn.flying.dao.entity.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Named aggregate row without tenant business data. */
@Getter
@Setter
@Accessors(chain = true)
public class PlatformOverviewRow {
    private Long tenants;
    private Long activeTenants;
    private Long disabledTenants;
    private Long users;
    private Long activeUsers;
}
