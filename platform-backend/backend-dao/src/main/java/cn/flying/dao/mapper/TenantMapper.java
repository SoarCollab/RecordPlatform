package cn.flying.dao.mapper;

import cn.flying.dao.entity.Tenant;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id FROM tenant WHERE status = 1")
    List<Long> selectActiveTenantIds();
}
