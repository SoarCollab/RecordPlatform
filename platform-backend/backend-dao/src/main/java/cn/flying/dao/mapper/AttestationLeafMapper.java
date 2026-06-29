package cn.flying.dao.mapper;

import cn.flying.dao.entity.AttestationLeaf;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for Merkle attestation leaves.
 */
@Mapper
public interface AttestationLeafMapper extends BaseMapper<AttestationLeaf> {
}
