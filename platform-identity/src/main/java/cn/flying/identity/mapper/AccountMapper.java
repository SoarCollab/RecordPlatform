package cn.flying.identity.mapper;

import cn.flying.identity.dto.Account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户数据访问层接口
 * 继承MyBatis-Plus的BaseMapper，提供基础CRUD操作
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /**
     * 根据用户名或邮箱查找用户
     *
     * @param text 用户名或邮箱
     * @return 用户实体
     */
    @Select("SELECT * FROM account WHERE (username = #{text} OR email = #{text}) AND deleted = 0")
    Account findAccountByNameOrEmail(@Param("text") String text);

    /**
     * 根据邮箱查找用户
     *
     * @param email 邮箱
     * @return 用户实体
     */
    @Select("SELECT * FROM account WHERE email = #{email} AND deleted = 0")
    Account findAccountByEmail(@Param("email") String email);

    /**
     * 根据用户名查找用户
     *
     * @param username 用户名
     * @return 用户实体
     */
    @Select("SELECT * FROM account WHERE username = #{username} AND deleted = 0")
    Account findAccountByUsername(@Param("username") String username);
}