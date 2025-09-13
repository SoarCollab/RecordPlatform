package cn.flying.identity.mapper;

import cn.flying.identity.dto.OAuthClient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * OAuth2.0客户端Mapper接口
 * 提供客户端信息的数据库操作方法
 */
@Mapper
public interface OAuthClientMapper extends BaseMapper<OAuthClient> {

    /**
     * 根据客户端标识符查找客户端
     *
     * @param clientKey 客户端标识符
     * @return 客户端信息
     */
    @Select("SELECT * FROM oauth_client WHERE client_key = #{clientKey} AND deleted = 0")
    OAuthClient findByClientKey(@Param("clientKey") String clientKey);

    // 已删除不安全的密钥比较方法 findByClientKeyAndSecret
    // 客户端验证现在统一通过 OAuthClientSecretService 进行安全验证

    /**
     * 根据客户端名称查找客户端
     *
     * @param clientName 客户端名称
     * @return 客户端信息
     */
    @Select("SELECT * FROM oauth_client WHERE client_name = #{clientName} AND deleted = 0")
    OAuthClient findByClientName(@Param("clientName") String clientName);


    /**
     * 根据状态查找客户端
     *
     * @param status 客户端状态
     * @return 客户端列表
     */
    @Select("SELECT * FROM oauth_client WHERE status = #{status} AND deleted = 0 ORDER BY create_time DESC")
    List<OAuthClient> findByStatus(@Param("status") Integer status);

    /**
     * 更新客户端状态
     *
     * @param clientKey 客户端标识符
     * @param status 新状态
     * @return 更新行数
     */
    @Update("UPDATE oauth_client SET status = #{status} WHERE client_key = #{clientKey} AND deleted = 0")
    int updateStatus(@Param("clientKey") String clientKey, @Param("status") Integer status);
}