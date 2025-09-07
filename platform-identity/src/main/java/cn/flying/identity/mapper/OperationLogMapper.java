package cn.flying.identity.mapper;

import cn.flying.identity.dto.OperationLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper 接口
 * 
 * @author 王贝强
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
}
