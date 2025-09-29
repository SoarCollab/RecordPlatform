package cn.flying.identity.mapper.apigateway;

import cn.flying.identity.dto.apigateway.GatewayPlugin;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 网关插件配置Mapper接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Mapper
public interface GatewayPluginMapper extends BaseMapper<GatewayPlugin> {
}
