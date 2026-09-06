package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.platform.PlatformConfigurationEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Safe code-owned keys on the already-global audit configuration table. */
@Mapper
public interface PlatformConfigurationMapper {

    String SAFE_KEYS = "('HIGH_FREQ_THRESHOLD', 'FAILED_LOGIN_THRESHOLD', 'ERROR_RATE_THRESHOLD', 'LOG_RETENTION_DAYS')";
    String COLUMNS = "config_key, config_value, version";

    /** Reads a fixed-size registry, omitting arbitrary keys and persisted descriptions. */
    @Select("SELECT " + COLUMNS + " FROM sys_audit_config WHERE config_key IN " + SAFE_KEYS + " ORDER BY config_key")
    List<PlatformConfigurationEntry> selectSafeEntries();

    /** Reads one allowlisted row without disclosing unrelated configuration. */
    @Select("SELECT " + COLUMNS + " FROM sys_audit_config WHERE config_key = #{key} AND config_key IN " + SAFE_KEYS)
    PlatformConfigurationEntry selectEntry(@Param("key") String key);

    /** Locks a safe value for optimistic replacement after the system configuration fence. */
    @Select("SELECT " + COLUMNS + " FROM sys_audit_config WHERE config_key = #{key} AND config_key IN "
            + SAFE_KEYS + " FOR UPDATE")
    PlatformConfigurationEntry lockEntry(@Param("key") String key);

    /** Replaces a value only for a matching version and code-owned key. */
    @Update("UPDATE sys_audit_config SET config_value = #{value}, version = version + 1, "
            + "update_time = CURRENT_TIMESTAMP WHERE config_key = #{key} AND version = #{expectedVersion} "
            + "AND config_key IN " + SAFE_KEYS)
    int updateEntry(@Param("key") String key, @Param("value") String value,
                    @Param("expectedVersion") Long expectedVersion);

    /** Restores a missing allowlisted row with code-owned metadata and first-write version one. */
    @Insert("INSERT INTO sys_audit_config (config_key, config_value, description, version) "
            + "SELECT #{key}, #{value}, #{description}, 1 WHERE #{key} IN " + SAFE_KEYS)
    int insertEntry(@Param("key") String key, @Param("value") String value,
                    @Param("description") String description);
}
