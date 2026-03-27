package cn.flying.dao.mapper;

import cn.flying.dao.entity.IntegrityAlert;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for integrity_alert table.
 */
@Mapper
public interface IntegrityAlertMapper extends BaseMapper<IntegrityAlert> {

    /**
     * Select pending alerts for a given tenant.
     *
     * @param tenantId tenant ID
     * @return list of pending alerts ordered by creation time descending
     */
    @Select("SELECT * FROM integrity_alert WHERE tenant_id = #{tenantId} AND status = 0 ORDER BY create_time DESC")
    List<IntegrityAlert> selectPendingAlerts(@Param("tenantId") Long tenantId);
}
