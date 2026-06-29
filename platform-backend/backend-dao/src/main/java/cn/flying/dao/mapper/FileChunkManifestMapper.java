package cn.flying.dao.mapper;

import cn.flying.dao.entity.FileChunkManifest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for file_chunk_manifest table.
 */
@Mapper
public interface FileChunkManifestMapper extends BaseMapper<FileChunkManifest> {
}
