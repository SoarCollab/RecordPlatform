package cn.flying.dao.mapper;

import cn.flying.dao.dto.FileSource;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文件来源链 Mapper
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Mapper
public interface FileSourceMapper extends BaseMapper<FileSource> {

    /**
     * 根据文件ID查询来源记录
     *
     * @param fileId 文件ID
     * @param tenantId 租户ID
     * @return 来源记录
     */
    @Select("SELECT id, tenant_id, file_id, origin_file_id, source_file_id, source_user_id, share_code, depth, create_time " +
            "FROM file_source WHERE file_id = #{fileId} AND tenant_id = #{tenantId}")
    FileSource selectByFileId(@Param("fileId") Long fileId, @Param("tenantId") Long tenantId);

    /**
     * 获取来源文件的来源记录（用于计算链路深度）
     *
     * @param sourceFileId 来源文件ID
     * @param tenantId 租户ID
     * @return 来源记录
     */
    @Select("SELECT id, tenant_id, file_id, origin_file_id, source_file_id, source_user_id, share_code, depth, create_time " +
            "FROM file_source WHERE file_id = #{sourceFileId} AND tenant_id = #{tenantId}")
    FileSource selectBySourceFileId(@Param("sourceFileId") Long sourceFileId, @Param("tenantId") Long tenantId);

    /**
     * 递归查询文件的完整分享链路（从当前文件追溯到原始文件）
     * 结果按深度降序排列（从远到近）
     *
     * @param fileId 文件ID
     * @param tenantId 租户ID
     * @return 分享链路列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        WITH RECURSIVE chain AS (
            SELECT
                fs.id,
                fs.tenant_id,
                fs.file_id,
                fs.origin_file_id,
                fs.source_file_id,
                fs.source_user_id,
                fs.share_code,
                fs.depth,
                fs.create_time,
                1 as level
            FROM file_source fs
            WHERE fs.file_id = #{fileId} AND fs.tenant_id = #{tenantId}
            UNION ALL
            SELECT
                parent.id,
                parent.tenant_id,
                parent.file_id,
                parent.origin_file_id,
                parent.source_file_id,
                parent.source_user_id,
                parent.share_code,
                parent.depth,
                parent.create_time,
                chain.level + 1
            FROM file_source parent
            INNER JOIN chain ON parent.file_id = chain.source_file_id AND parent.tenant_id = #{tenantId}
        )
        SELECT id, tenant_id, file_id, origin_file_id, source_file_id, source_user_id, share_code, depth, create_time
        FROM chain
        ORDER BY level DESC
        """)
    List<FileSource> selectProvenanceChain(@Param("fileId") Long fileId, @Param("tenantId") Long tenantId);

    /**
     * 查询同一原始文件的所有传播记录
     *
     * @param originFileId 原始文件ID
     * @param tenantId 租户ID
     * @return 传播记录列表
     */
    @Select("SELECT id, tenant_id, file_id, origin_file_id, source_file_id, source_user_id, share_code, depth, create_time " +
            "FROM file_source WHERE origin_file_id = #{originFileId} AND tenant_id = #{tenantId} ORDER BY depth, create_time")
    List<FileSource> selectByOriginFileId(@Param("originFileId") Long originFileId, @Param("tenantId") Long tenantId);

    /**
     * 查询某用户分享出去的文件被保存的记录
     *
     * @param sourceUserId 来源用户ID
     * @param tenantId 租户ID
     * @return 保存记录列表
     */
    @Select("SELECT id, tenant_id, file_id, origin_file_id, source_file_id, source_user_id, share_code, depth, create_time " +
            "FROM file_source WHERE source_user_id = #{sourceUserId} AND tenant_id = #{tenantId} ORDER BY create_time DESC")
    List<FileSource> selectBySourceUserId(@Param("sourceUserId") Long sourceUserId, @Param("tenantId") Long tenantId);
}
