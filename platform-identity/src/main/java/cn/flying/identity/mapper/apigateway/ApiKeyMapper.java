package cn.flying.identity.mapper.apigateway;

import cn.flying.identity.dto.apigateway.ApiKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * API密钥管理Mapper接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
