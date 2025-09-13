package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

/**
 * 短信服务接口 - 基于SMS4J框架实现
 * 提供统一的短信发送功能，支持多种短信服务提供商
 *
 * @author flying
 * @date 2025-01-16
 */
public interface SmsService {

    /**
     * 发送短信验证码
     * 使用SMS4J框架发送短信
     *
     * @param phone    手机号码
     * @param code     验证码
     * @param type     短信类型（register/reset/modify/login）
     * @param supplier 短信服务提供商（可选，不传则使用默认配置）
     * @return 发送结果
     */
    Result<Boolean> sendVerifyCode(String phone, String code, String type, String supplier);

    /**
     * 发送短信验证码（使用默认提供商）
     *
     * @param phone 手机号码
     * @param code  验证码
     * @param type  短信类型
     * @return 发送结果
     */
    Result<Boolean> sendVerifyCode(String phone, String code, String type);

    /**
     * 发送普通短信
     *
     * @param phone    手机号码
     * @param content  短信内容
     * @param supplier 短信服务提供商（可选）
     * @return 发送结果
     */
    Result<Boolean> sendMessage(String phone, String content, String supplier);

    /**
     * 发送普通短信（使用默认提供商）
     *
     * @param phone   手机号码
     * @param content 短信内容
     * @return 发送结果
     */
    Result<Boolean> sendMessage(String phone, String content);

    /**
     * 检查短信服务是否可用
     *
     * @param supplier 短信服务提供商
     * @return 是否可用
     */
    Result<Boolean> isServiceAvailable(String supplier);

    /**
     * 获取可用的短信服务提供商列表
     *
     * @return 提供商列表
     */
    Result<java.util.List<String>> getAvailableSuppliers();

    /**
     * 获取当前使用的默认短信服务提供商
     *
     * @return 提供商名称
     */
    String getDefaultSupplier();

    /**
     * 检查短信发送功能是否启用
     *
     * @return true=短信功能已禁用，false=短信功能已启用
     */
    boolean isSmsRestricted();
}
