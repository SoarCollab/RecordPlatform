package cn.flying.dao.mapper;

import cn.flying.dao.dto.Account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @program: RecordPlatform
 * @description: 用户mapper接口类
 * @create: 2025-01-16 14:55
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {
}
