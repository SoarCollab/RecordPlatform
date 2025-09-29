package cn.flying.identity.service.apigateway;

import cn.flying.identity.dto.apigateway.ApiApplication;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Map;

/**
 * API应用管理服务接口
 * 提供应用注册、审核、管理等功能
 *
 * @author 王贝强
 * @since 2025-10-11
 */
public interface ApiApplicationService {

    /**
     * 注册新应用
     * 开发者提交应用注册申请,等待管理员审核
     *
     * @param appName        应用名称
     * @param appDescription 应用描述
     * @param ownerId        所属开发者用户ID
     * @param appType        应用类型:1-Web应用,2-移动应用,3-服务端应用,4-其他
     * @param appWebsite     应用官网
     * @param callbackUrl    回调URL(多个用逗号分隔)
     * @return 注册结果,包含应用ID和应用标识码
     */
    Result<Map<String, Object>> registerApplication(String appName, String appDescription,
                                                    Long ownerId, Integer appType,
                                                    String appWebsite, String callbackUrl);

    /**
     * 审核应用
     * 管理员审核应用注册申请,通过或拒绝
     *
     * @param appId        应用ID
     * @param approved     是否通过审核
     * @param approveBy    审核人ID
     * @param rejectReason 拒绝原因(审核不通过时必填)
     * @return 审核结果
     */
    Result<Void> approveApplication(Long appId, boolean approved, Long approveBy, String rejectReason);

    /**
     * 启用应用
     *
     * @param appId 应用ID
     * @return 操作结果
     */
    Result<Void> enableApplication(Long appId);

    /**
     * 禁用应用
     * 禁用后该应用的所有API密钥将无法使用
     *
     * @param appId  应用ID
     * @param reason 禁用原因
     * @return 操作结果
     */
    Result<Void> disableApplication(Long appId, String reason);

    /**
     * 删除应用
     * 软删除,同时删除该应用的所有密钥
     *
     * @param appId 应用ID
     * @return 操作结果
     */
    Result<Void> deleteApplication(Long appId);

    /**
     * 更新应用信息
     *
     * @param application 应用信息
     * @return 更新结果
     */
    Result<Void> updateApplication(ApiApplication application);

    /**
     * 获取应用详情
     *
     * @param appId 应用ID
     * @return 应用详情
     */
    Result<ApiApplication> getApplicationById(Long appId);

    /**
     * 根据应用标识码获取应用
     *
     * @param appCode 应用标识码
     * @return 应用信息
     */
    Result<ApiApplication> getApplicationByCode(String appCode);

    /**
     * 获取用户的所有应用
     *
     * @param ownerId 用户ID
     * @return 应用列表
     */
    Result<List<ApiApplication>> getApplicationsByOwner(Long ownerId);

    /**
     * 分页查询应用列表
     *
     * @param pageNum   页码
     * @param pageSize  每页大小
     * @param appStatus 应用状态(可选)
     * @param keyword   搜索关键词(可选,搜索应用名称或标识码)
     * @return 分页结果
     */
    Result<Page<ApiApplication>> getApplicationsPage(int pageNum, int pageSize,
                                                     Integer appStatus, String keyword);

    /**
     * 更新应用IP白名单
     *
     * @param appId       应用ID
     * @param ipWhitelist IP白名单列表(JSON数组字符串)
     * @return 更新结果
     */
    Result<Void> updateIpWhitelist(Long appId, String ipWhitelist);

    /**
     * 验证IP是否在应用白名单中
     *
     * @param appId    应用ID
     * @param clientIp 客户端IP
     * @return 是否允许访问
     */
    Result<Boolean> validateIpWhitelist(Long appId, String clientIp);

    /**
     * 获取应用统计信息
     * 包括API调用次数、成功率、配额使用情况等
     *
     * @param appId 应用ID
     * @param days  统计天数
     * @return 统计信息
     */
    Result<Map<String, Object>> getApplicationStatistics(Long appId, int days);

    /**
     * 获取待审核应用列表
     *
     * @return 待审核应用列表
     */
    Result<List<ApiApplication>> getPendingApplications();

    /**
     * 验证回调URL格式
     *
     * @param callbackUrls 回调URL列表(逗号分隔)
     * @return 验证结果
     */
    Result<Boolean> validateCallbackUrls(String callbackUrls);
}
