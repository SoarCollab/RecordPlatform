package cn.flying.identity.mapper;

import cn.flying.identity.dto.OAuthCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * OAuth2.0授权码Mapper接口
 * 提供授权码的数据库操作方法
 */
@Mapper
public interface OAuthCodeMapper extends BaseMapper<OAuthCode> {

    /**
     * 根据授权码查找记录
     *
     * @param code 授权码
     * @return 授权码记录
     */
    @Select("SELECT * FROM oauth_code WHERE code = #{code}")
    OAuthCode findByCode(@Param("code") String code);

    /**
     * 根据授权码和客户端标识符查找记录
     *
     * @param code      授权码
     * @param clientKey 客户端标识符
     * @return 授权码记录
     */
    @Select("SELECT * FROM oauth_code WHERE code = #{code} AND client_key = #{clientKey}")
    OAuthCode findByCodeAndClientKey(@Param("code") String code, @Param("clientKey") String clientKey);

    /**
     * 标记授权码为已使用
     *
     * @param code 授权码
     * @return 更新行数
     */
    @Update("UPDATE oauth_code SET status = 0, used_time = NOW() WHERE code = #{code}")
    int markCodeAsUsed(@Param("code") String code);

    /**
     * 清理过期的授权码
     *
     * @return 清理的记录数
     */
    @Update("UPDATE oauth_code SET status = -1 WHERE expire_time < NOW() AND status = 1")
    int cleanExpiredCodes();

    /**
     * 删除用户的所有授权码
     *
     * @param userId 用户ID
     * @return 删除的记录数
     */
    @Update("DELETE FROM oauth_code WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}