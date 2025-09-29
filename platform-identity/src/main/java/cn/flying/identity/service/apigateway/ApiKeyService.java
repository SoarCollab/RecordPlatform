package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.platformapi.constant.Result;

import java.util.List;
import java.util.Map;

/**
 * API密钥管理服务接口
 * 提供密钥生成、验证、轮换等核心功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiKeyService {

    /**
     * 生成新的API密钥对
     * 为指定应用生成ApiKey和ApiSecret,支持AES-256加密存储
     *
     * @param appId      应用ID
     * @param keyName    密钥名称
     * @param keyType    密钥类型:1-正式环境,2-测试环境
     * @param expireDays 过期天数(NULL表示永久)
     * @return 生成的密钥信息
     */
    Result<Map<String, Object>> generateApiKey(Long appId, String keyName, Integer keyType, Integer expireDays);

    /**
     * 验证API密钥
     * 验证ApiKey和签名是否有效,支持HMAC-SHA256签名验证
     *
     * @param apiKey      API密钥
     * @param timestamp   时间戳(用于防重放攻击)
     * @param nonce       随机字符串(用于防重放攻击)
     * @param signature   签名
     * @param requestData 请求数据
     * @return 验证结果,包含应用ID和密钥信息
     */
    Result<Map<String, Object>> validateApiKey(String apiKey, Long timestamp, String nonce,
                                               String signature, String requestData);

    /**
     * 启用密钥
     *
     * @param keyId 密钥ID
     * @return 操作结果
     */
    Result<Void> enableKey(Long keyId);

    /**
     * 禁用密钥
     *
     * @param keyId 密钥ID
     * @return 操作结果
     */
    Result<Void> disableKey(Long keyId);

    /**
     * 删除密钥
     *
     * @param keyId 密钥ID
     * @return 操作结果
     */
    Result<Void> deleteKey(Long keyId);

    /**
     * 轮换密钥
     * 创建新密钥并禁用旧密钥,支持平滑迁移
     *
     * @param oldKeyId 旧密钥ID
     * @return 新密钥信息
     */
    Result<Map<String, Object>> rotateKey(Long oldKeyId);

    /**
     * 获取应用的所有密钥列表
     *
     * @param appId 应用ID
     * @return 密钥列表
     */
    Result<List<ApiKey>> getKeysByAppId(Long appId);

    /**
     * 获取密钥详情
     *
     * @param keyId 密钥ID
     * @return 密钥详情
     */
    Result<ApiKey> getKeyById(Long keyId);

    /**
     * 更新密钥最后使用时间
     * 用于统计密钥使用情况
     *
     * @param keyId 密钥ID
     * @return 操作结果
     */
    Result<Void> updateLastUsedTime(Long keyId);

    /**
     * 检查密钥是否即将过期
     * 用于提前告警
     *
     * @param days 提前天数
     * @return 即将过期的密钥列表
     */
    Result<List<ApiKey>> getExpiringKeys(int days);

    /**
     * 简化版API密钥验证
     * 仅验证密钥是否存在和有效，不进行签名验证
     * 用于内部快速验证，如网关过滤器的初步验证
     *
     * @param apiKey API密钥
     * @return 验证结果
     */
    Result<Void> validateApiKey(String apiKey);

    /**
     * 根据ApiKey获取完整的密钥信息
     * 用于获取密钥关联的应用ID等信息
     *
     * @param apiKey API密钥
     * @return 密钥完整信息（包含appId、keyStatus等）
     */
    Result<ApiKey> getKeyInfoByApiKey(String apiKey);
}
