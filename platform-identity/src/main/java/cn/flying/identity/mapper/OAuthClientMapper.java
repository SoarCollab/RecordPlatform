package cn.flying.identity.mapper;

import cn.flying.identity.dto.OAuthClient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 根据客户端标识符和密钥查找客户端
     *
     * @param clientKey    客户端标识符
     * @param clientSecret 客户端密钥
     * @return 客户端信息
     */
    @Select("SELECT * FROM oauth_client WHERE client_key = #{clientKey} AND client_secret = #{clientSecret} AND deleted = 0")
    OAuthClient findByClientKeyAndSecret(@Param("clientKey") String clientKey, @Param("clientSecret") String clientSecret);

    /**
     * 根据客户端名称查找客户端
     *
     * @param clientName 客户端名称
     * @return 客户端信息
     */
    @Select("SELECT * FROM oauth_client WHERE client_name = #{clientName} AND deleted = 0")
    OAuthClient findByClientName(@Param("clientName") String clientName);
}