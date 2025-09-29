package cn.flying.identity.mapper.apigateway;

import cn.flying.identity.dto.apigateway.ApiRoute;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * API路由配置数据访问层
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Mapper
public interface ApiRouteMapper extends BaseMapper<ApiRoute> {
}