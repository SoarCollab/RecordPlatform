package cn.flying.identity.service;

import cn.flying.identity.dto.Account;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;

/**
 * 认证服务接口
 * 定义用户认证、注册、密码管理等核心功能
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param username 用户名或邮箱
     * @param password 密码
     * @return 登录结果，包含Token信息
     */
    Result<String> login(String username, String password);

    /**
     * 用户注销
     *
     * @return 注销结果
     */
    Result<Void> logout();

    /**
     * 用户注册
     *
     * @param vo 注册信息
     * @return 注册结果
     */
    Result<Void> register(EmailRegisterVO vo);

    /**
     * 发送邮箱验证码
     *
     * @param email 邮箱地址
     * @param type  验证码类型（register/reset）
     * @return 发送结果
     */
    Result<Void> askVerifyCode(String email, String type);

    /**
     * 重置密码确认
     *
     * @param vo 重置密码信息
     * @return 重置结果
     */
    Result<Void> resetConfirm(EmailResetVO vo);

    /**
     * 修改密码
     *
     * @param vo 修改密码信息
     * @return 修改结果
     */
    Result<Void> changePassword(ChangePasswordVO vo);

    /**
     * 获取当前登录用户信息
     *
     * @return 用户信息
     */
    Result<AccountVO> getUserInfo();

    /**
     * 根据用户名或邮箱查找用户
     *
     * @param text 用户名或邮箱
     * @return 用户实体
     */
    Account findAccountByNameOrEmail(String text);

    /**
     * 检查登录状态
     *
     * @return 登录状态信息
     */
    Result<Object> checkLoginStatus();

    /**
     * 获取Token信息
     *
     * @return Token详细信息
     */
    Result<Object> getTokenInfo();
}