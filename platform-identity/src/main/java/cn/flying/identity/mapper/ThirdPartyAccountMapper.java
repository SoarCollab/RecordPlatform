package cn.flying.identity.mapper;

import cn.flying.identity.dto.ThirdPartyAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 第三方账号绑定数据访问层
 * 提供第三方账号绑定关系的数据库操作接口
 * 
 * @author 王贝强
 */
@Mapper
public interface ThirdPartyAccountMapper extends BaseMapper<ThirdPartyAccount> {

    /**
     * 根据用户ID和第三方平台提供商查找绑定账号
     * 
     * @param userId 用户ID
     * @param provider 第三方平台提供商
     * @return 第三方绑定账号信息
     */
    ThirdPartyAccount findByUserIdAndProvider(@Param("userId") Long userId, @Param("provider") String provider);

    /**
     * 根据第三方平台提供商和第三方用户ID查找绑定账号
     * 
     * @param provider 第三方平台提供商
     * @param thirdPartyId 第三方平台用户ID
     * @return 第三方绑定账号信息
     */
    ThirdPartyAccount findByProviderAndThirdPartyId(@Param("provider") String provider, @Param("thirdPartyId") String thirdPartyId);

    /**
     * 根据用户ID获取所有绑定的第三方账号
     * 
     * @param userId 用户ID
     * @return 第三方绑定账号列表
     */
    List<ThirdPartyAccount> findByUserId(@Param("userId") Long userId);

    /**
     * 删除用户的指定第三方平台绑定
     * 
     * @param userId 用户ID
     * @param provider 第三方平台提供商
     * @return 影响的记录数
     */
    int deleteByUserIdAndProvider(@Param("userId") Long userId, @Param("provider") String provider);

    /**
     * 更新第三方账号的访问令牌信息
     * 
     * @param userId 用户ID
     * @param provider 第三方平台提供商
     * @param accessToken 新的访问令牌
     * @param refreshToken 新的刷新令牌
     * @param expiresAt 令牌过期时间
     * @return 影响的记录数
     */
    int updateTokens(@Param("userId") Long userId, 
                    @Param("provider") String provider,
                    @Param("accessToken") String accessToken, 
                    @Param("refreshToken") String refreshToken,
                    @Param("expiresAt") java.time.LocalDateTime expiresAt);
}
