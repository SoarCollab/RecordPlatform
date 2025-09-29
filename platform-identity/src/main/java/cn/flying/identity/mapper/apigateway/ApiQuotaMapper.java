package cn.flying.identity.mapper.apigateway;

import cn.flying.identity.dto.apigateway.ApiQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * API配额限制Mapper接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Mapper
public interface ApiQuotaMapper extends BaseMapper<ApiQuota> {
}
