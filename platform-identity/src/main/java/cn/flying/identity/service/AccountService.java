package cn.flying.identity.service;

import cn.flying.identity.dto.Account;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.ConfirmResetVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.identity.vo.request.ModifyEmailVO;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 账户服务接口
 * 从 platform-backend 迁移而来，适配 SA-Token 框架
 * 提供用户账户管理的核心功能
 * 
 * @author 王贝强
 */
public interface AccountService extends IService<Account> {
    
    /**
     * 通过用户名或邮箱查找用户
     * 
     * @param text 用户名或邮箱
     * @return 用户账户信息
     */
    Account findAccountByNameOrEmail(String text);
    
    /**
     * 通过用户ID查找用户
     * 
     * @param id 用户ID
     * @return 用户账户信息
     */
    Account findAccountById(Long id);
    
    /**
     * 发送邮箱验证码
     * 
     * @param type 验证码类型（register/reset）
     * @param email 邮箱地址
     * @param address 请求IP地址
     * @return 操作结果
     */
    Result<Void> registerEmailVerifyCode(String type, String email, String address);
    
    /**
     * 邮箱验证码注册账号
     * 
     * @param info 注册信息
     * @return 操作结果
     */
    Result<Void> registerEmailAccount(EmailRegisterVO info);
    
    /**
     * 重置密码
     * 
     * @param info 重置密码信息
     * @return 操作结果
     */
    Result<Void> resetEmailAccountPassword(EmailResetVO info);
    
    /**
     * 确认重置密码
     * 
     * @param info 确认重置信息
     * @return 操作结果
     */
    Result<Void> resetConfirm(ConfirmResetVO info);
    
    /**
     * 修改邮箱
     * 
     * @param userId 用户ID
     * @param modifyEmailVO 修改邮箱信息
     * @return 操作结果
     */
    Result<Void> modifyEmail(Long userId, ModifyEmailVO modifyEmailVO);
    
    /**
     * 修改密码
     *
     * @param userId 用户ID
     * @param changePasswordVO 修改密码信息
     * @return 操作结果
     */
    Result<Void> changePassword(Long userId, ChangePasswordVO changePasswordVO);
    
    /**
     * 检查邮箱是否已存在
     * 
     * @param email 邮箱
     * @return 是否存在
     */
    boolean existsAccountByEmail(String email);
    
    /**
     * 检查用户名是否已存在
     * 
     * @param username 用户名
     * @return 是否存在
     */
    boolean existsAccountByUsername(String username);
    
    /**
     * 验证密码
     * 
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    boolean matchesPassword(String rawPassword, String encodedPassword);
    
    /**
     * 加密密码
     * 
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    String encodePassword(String rawPassword);
}
