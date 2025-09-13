package cn.flying.identity.service.impl;

import cn.flying.identity.service.SmsService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.dromara.sms4j.api.SmsBlend;
import org.dromara.sms4j.core.factory.SmsFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 短信服务实现类 - 基于SMS4J框架
 * 提供统一的短信发送功能，支持多种短信服务提供商
 * 
 * @author flying
 * @date 2025-01-16
 */
@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    // 默认短信服务提供商
    @Value("${sms.config.supplier:mock}")
    private String defaultSupplier;

    // 短信发送功能开关（true=禁用，false=启用）
    @Value("${sms.restricted:true}")
    private boolean smsRestricted;

    // 支持的短信服务提供商列表
    private static final List<String> SUPPORTED_SUPPLIERS = Arrays.asList(
            "alibaba", "tencent", "huawei", "mock"
    );

    @Override
    public Result<Boolean> sendVerifyCode(String phone, String code, String type, String supplier) {
        try {
            // 检查短信发送功能是否被禁用
            if (smsRestricted) {
                log.info("短信发送功能已禁用，跳过发送。手机号: {}, 类型: {}", phone, type);
                return Result.success(true);  // 返回成功，但实际不发送
            }

            // 参数校验
            if (StrUtil.isBlank(phone) || StrUtil.isBlank(code) || StrUtil.isBlank(type)) {
                log.warn("短信发送参数不完整，手机号: {}, 验证码: {}, 类型: {}", phone, code, type);
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 如果没有指定提供商，使用默认提供商
            String actualSupplier = StrUtil.isBlank(supplier) ? defaultSupplier : supplier;

            log.info("准备发送短信验证码，手机号: {}, 类型: {}, 提供商: {}", phone, type, actualSupplier);

            // 使用SMS4J发送短信
            SmsBlend smsBlend = SmsFactory.getSmsBlend(actualSupplier);
            var response = smsBlend.sendMessage(phone, code);

            if (response != null && response.isSuccess()) {
                log.info("短信验证码发送成功，手机号: {}, 类型: {}, 提供商: {}", phone, type, actualSupplier);
                return Result.success(true);
            } else {
                String errorMsg = response != null ? response.toString() : "未知错误";
                log.error("短信验证码发送失败，手机号: {}, 类型: {}, 提供商: {}, 错误: {}", 
                         phone, type, actualSupplier, errorMsg);
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

        } catch (Exception e) {
            log.error("短信验证码发送异常，手机号: {}, 类型: {}, 提供商: {}", phone, type, supplier, e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> sendVerifyCode(String phone, String code, String type) {
        return sendVerifyCode(phone, code, type, defaultSupplier);
    }

    @Override
    public Result<Boolean> sendMessage(String phone, String content, String supplier) {
        try {
            // 检查短信发送功能是否被禁用
            if (smsRestricted) {
                log.info("短信发送功能已禁用，跳过发送。手机号: {}", phone);
                return Result.success(true);  // 返回成功，但实际不发送
            }

            // 参数校验
            if (StrUtil.isBlank(phone) || StrUtil.isBlank(content)) {
                log.warn("短信发送参数不完整，手机号: {}, 内容: {}", phone, content);
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

            // 如果没有指定提供商，使用默认提供商
            String actualSupplier = StrUtil.isBlank(supplier) ? defaultSupplier : supplier;

            log.info("准备发送普通短信，手机号: {}, 提供商: {}", phone, actualSupplier);

            // 使用SMS4J发送短信
            SmsBlend smsBlend = SmsFactory.getSmsBlend(actualSupplier);
            var response = smsBlend.sendMessage(phone, content);

            if (response != null && response.isSuccess()) {
                log.info("普通短信发送成功，手机号: {}, 提供商: {}", phone, actualSupplier);
                return Result.success(true);
            } else {
                String errorMsg = response != null ? response.toString() : "未知错误";
                log.error("普通短信发送失败，手机号: {}, 提供商: {}, 错误: {}", 
                         phone, actualSupplier, errorMsg);
                return Result.error(ResultEnum.SYSTEM_ERROR, null);
            }

        } catch (Exception e) {
            log.error("普通短信发送异常，手机号: {}, 提供商: {}", phone, supplier, e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public Result<Boolean> sendMessage(String phone, String content) {
        return sendMessage(phone, content, defaultSupplier);
    }

    @Override
    public Result<Boolean> isServiceAvailable(String supplier) {
        try {
            // 如果短信功能被禁用，返回不可用
            if (smsRestricted) {
                log.debug("短信发送功能已禁用，服务不可用");
                return Result.success(false);
            }

            String actualSupplier = StrUtil.isBlank(supplier) ? defaultSupplier : supplier;
            
            if (!SUPPORTED_SUPPLIERS.contains(actualSupplier)) {
                log.warn("不支持的短信服务提供商: {}", actualSupplier);
                return Result.success(false);
            }

            // 尝试获取SMS4J服务实例
            SmsBlend smsBlend = SmsFactory.getSmsBlend(actualSupplier);
            boolean available = smsBlend != null;
            
            log.debug("短信服务可用性检查，提供商: {}, 可用: {}", actualSupplier, available);
            return Result.success(available);

        } catch (Exception e) {
            log.error("检查短信服务可用性异常，提供商: {}", supplier, e);
            return Result.success(false);
        }
    }

    @Override
    public Result<List<String>> getAvailableSuppliers() {
        try {
            // 返回支持的提供商列表
            log.debug("获取可用的短信服务提供商列表: {}", SUPPORTED_SUPPLIERS);
            return Result.success(SUPPORTED_SUPPLIERS);
        } catch (Exception e) {
            log.error("获取短信服务提供商列表异常", e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }

    @Override
    public String getDefaultSupplier() {
        return defaultSupplier;
    }

    @Override
    public boolean isSmsRestricted() {
        return smsRestricted;
    }

    /**
     * 验证手机号格式
     *
     * @param phone 手机号
     * @return 是否有效
     */
    private boolean isValidPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return false;
        }
        // 中国大陆手机号格式验证
        return phone.matches("^1[3-9]\\d{9}$");
    }

    /**
     * 验证短信内容长度
     *
     * @param content 短信内容
     * @return 是否有效
     */
    private boolean isValidContent(String content) {
        if (StrUtil.isBlank(content)) {
            return false;
        }
        // 短信内容长度限制（一般不超过500字符）
        return content.length() <= 500;
    }
}
