package cn.flying.dao.mapper;

import cn.flying.dao.entity.Tenant;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM tenant WHERE status = 1 AND deleted = 0")
    List<Long> selectActiveTenantIds();

    /** Loads only fields required for current tenant authorization. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, status, version, deleted
              FROM tenant
             WHERE id = #{tenantId}
             LIMIT 1
            """)
    Tenant selectAuthorizationState(@Param("tenantId") Long tenantId);

    /** Serializes first-platform-administrator creation across backend replicas. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM tenant WHERE id = 0 FOR UPDATE")
    Long lockSystemTenantForPlatformBootstrap();

    /** Serializes tenant administrator mutations to preserve the last active administrator. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM tenant WHERE id = #{tenantId} AND deleted = 0 FOR UPDATE")
    Long lockTenantForMemberMutation(@Param("tenantId") Long tenantId);
}
