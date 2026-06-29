package cn.flying.dao.mapper;

import cn.flying.dao.entity.AttestationBatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for Merkle attestation batches.
 */
@Mapper
public interface AttestationBatchMapper extends BaseMapper<AttestationBatch> {
}
