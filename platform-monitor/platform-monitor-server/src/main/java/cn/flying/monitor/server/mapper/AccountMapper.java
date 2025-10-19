package cn.flying.monitor.server.mapper;

import cn.flying.monitor.server.entity.dto.Account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 根据OAuth信息查找用户
     *
     * @param provider    OAuth提供者标识，如platform-identity
     * @param oauthUserId OAuth提供者系统中的用户ID
     * @return 匹配的Account对象，如果不存在返回null
     */
    @Select("SELECT * FROM account WHERE oauth_provider = #{provider} " +
            "AND oauth_user_id = #{oauthUserId}")
    Account findByOAuthUser(@Param("provider") String provider,
                            @Param("oauthUserId") Long oauthUserId);

    /**
     * 根据OAuth提供者和用户名查找用户
     *
     * @param provider OAuth提供者标识，如platform-identity
     * @param username 用户名
     * @return 匹配的Account对象，如果不存在返回null
     */
    @Select("SELECT * FROM account WHERE oauth_provider = #{provider} " +
            "AND username = #{username}")
    Account findByOAuthProviderAndUsername(@Param("provider") String provider,
                                           @Param("username") String username);
}
