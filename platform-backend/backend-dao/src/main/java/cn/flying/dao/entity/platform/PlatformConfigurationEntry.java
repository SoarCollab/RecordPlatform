package cn.flying.dao.entity.platform;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Internal registry row; never serialized directly. */
@Getter
@Setter
@Accessors(chain = true)
public class PlatformConfigurationEntry {
    private String configKey;
    private String configValue;
    private Long version;
}
