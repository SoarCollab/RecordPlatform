package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.constant.ApiGatewayConstants;
import cn.flying.identity.dto.apigateway.ApiApplication;
import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.mapper.apigateway.ApiApplicationMapper;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiApplicationService;
import cn.flying.identity.service.apigateway.ApiCallLogService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * API应用管理服务实现类
 * 提供应用注册、审核、管理等功能
 * <p>
 * 核心功能:
 * 1. 应用注册与审核流程
 * 2. 应用状态管理(启用/禁用/删除)
 * 3. IP白名单控制
 * 4. 回调URL验证
 * 5. 应用统计分析
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Service
public class ApiApplicationServiceImpl extends BaseService implements ApiApplicationService {

    /**
     * Redis键前缀
     */
    private static final String APP_CACHE_PREFIX = "api:app:";
    private static final String APP_STATS_PREFIX = "api:app:stats:";

    /**
     * IP地址正则表达式
     */
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    /**
     * CIDR格式IP正则表达式
     */
    private static final Pattern CIDR_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/(\\d|[1-2]\\d|3[0-2])$"
    );

    @Resource
    private ApiApplicationMapper applicationMapper;

    @Resource
    private ApiKeyMapper apiKeyMapper;

    @Resource
    private ApiCallLogService apiCallLogService;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> registerApplication(String appName, String appDescription,
                                                           Long ownerId, Integer appType,
                                                           String appWebsite, String callbackUrl) {
        return safeExecuteData(() -> {
            // 参数验证
            requireNonBlank(appName, "应用名称不能为空");
            requireNonNull(ownerId, "所属用户ID不能为空");
            requireNonNull(appType, "应用类型不能为空");
            requireCondition(appType, ApiGatewayConstants.AppType::isValid, "应用类型必须在1-4之间");

            // 验证回调URL格式
            if (isNotBlank(callbackUrl)) {
                Result<Boolean> urlValidation = validateCallbackUrls(callbackUrl);
                if (!isSuccess(urlValidation) || !urlValidation.getData()) {
                    throw new RuntimeException("回调URL格式不正确");
                }
            }

            // 生成唯一的应用标识码，添加重试限制防止死循环
            String appCode = generateAppCode();
            int retryCount = 0;

            // 检查应用标识码是否已存在，最多重试10次
            while (isAppCodeExists(appCode) && retryCount++ < ApiGatewayConstants.SystemConfig.MAX_APP_CODE_RETRY) {
                appCode = generateAppCode();
            }

            // 如果重试次数达到上限，抛出异常
            if (retryCount >= ApiGatewayConstants.SystemConfig.MAX_APP_CODE_RETRY) {
                throw new RuntimeException("无法生成唯一的应用标识码，请稍后重试");
            }

            // 构建应用实体
            ApiApplication application = new ApiApplication();
            application.setId(IdUtils.nextEntityId());
            application.setAppName(appName);
            application.setAppCode(appCode);
            application.setAppDescription(appDescription);
            application.setOwnerId(ownerId);
            application.setAppType(appType);
            application.setAppStatus(ApiGatewayConstants.AppStatus.PENDING); // 默认待审核
            application.setAppWebsite(appWebsite);
            application.setCallbackUrl(callbackUrl);

            // 保存到数据库
            int inserted = applicationMapper.insert(application);
            requireCondition(inserted, count -> count > 0, "注册应用失败");

            // 缓存应用信息
            cacheApplication(application);

            // 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("app_id", application.getId());
            result.put("app_code", appCode);
            result.put("app_name", appName);
            result.put("app_status", 0); // 待审核
            result.put("create_time", application.getCreateTime());
            result.put("message", "应用注册成功,等待管理员审核");

            logInfo("注册API应用成功: appId={}, appCode={}, ownerId={}", application.getId(), appCode, ownerId);
            return result;
        }, "注册应用失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> approveApplication(Long appId, boolean approved, Long approveBy, String rejectReason) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(approveBy, "审核人ID不能为空");

            if (!approved && isBlank(rejectReason)) {
                throw new RuntimeException("拒绝审核时必须提供原因");
            }

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            // 检查应用状态
            if (application.getAppStatus() != 0) {
                throw new RuntimeException("应用当前状态无法审核");
            }

            // 更新应用状态
            application.setAppStatus(approved ? 1 : 3); // 1-已启用, 3-已拒绝
            application.setApproveBy(approveBy);
            application.setApproveTime(LocalDateTime.now());

            int updated = applicationMapper.updateById(application);
            requireCondition(updated, count -> count > 0, "审核应用失败");

            // 更新缓存
            if (approved) {
                cacheApplication(application);
            } else {
                clearApplicationCache(application.getAppCode());
            }

            logInfo("审核API应用: appId={}, approved={}, approveBy={}", appId, approved, approveBy);
        }, "审核应用失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> enableApplication(Long appId) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            application.setAppStatus(1);
            int updated = applicationMapper.updateById(application);
            requireCondition(updated, count -> count > 0, "启用应用失败");

            // 更新缓存
            cacheApplication(application);

            logInfo("启用API应用成功: appId={}", appId);
        }, "启用应用失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> disableApplication(Long appId, String reason) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonBlank(reason, "禁用原因不能为空");

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            application.setAppStatus(2); // 2-已禁用
            int updated = applicationMapper.updateById(application);
            requireCondition(updated, count -> count > 0, "禁用应用失败");

            // 清除缓存
            clearApplicationCache(application.getAppCode());

            // 同时禁用该应用的所有API密钥
            disableAllKeysForApp(appId);

            logInfo("禁用API应用成功: appId={}, reason={}", appId, reason);
        }, "禁用应用失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> deleteApplication(Long appId) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            // 软删除应用
            int deleted = applicationMapper.deleteById(appId);
            requireCondition(deleted, count -> count > 0, "删除应用失败");

            // 清除缓存
            clearApplicationCache(application.getAppCode());

            // 软删除该应用的所有API密钥
            deleteAllKeysForApp(appId);

            logInfo("删除API应用成功: appId={}", appId);
        }, "删除应用失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateApplication(ApiApplication application) {
        return safeExecuteAction(() -> {
            requireNonNull(application, "应用信息不能为空");
            requireNonNull(application.getId(), "应用ID不能为空");

            // 验证回调URL格式
            if (isNotBlank(application.getCallbackUrl())) {
                Result<Boolean> urlValidation = validateCallbackUrls(application.getCallbackUrl());
                if (!isSuccess(urlValidation) || !urlValidation.getData()) {
                    throw new RuntimeException("回调URL格式不正确");
                }
            }

            int updated = applicationMapper.updateById(application);
            requireCondition(updated, count -> count > 0, "更新应用失败");

            // 更新缓存
            cacheApplication(application);

            logInfo("更新API应用成功: appId={}", application.getId());
        }, "更新应用失败");
    }

    @Override
    public Result<ApiApplication> getApplicationById(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            return application;
        }, "查询应用失败");
    }

    @Override
    public Result<ApiApplication> getApplicationByCode(String appCode) {
        return safeExecuteData(() -> {
            requireNonBlank(appCode, "应用标识码不能为空");

            // 先从缓存获取
            ApiApplication cachedApp = getApplicationFromCache(appCode);
            if (cachedApp != null) {
                return cachedApp;
            }

            // 缓存未命中,从数据库查询
            LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiApplication::getAppCode, appCode);
            ApiApplication application = applicationMapper.selectOne(wrapper);

            if (application != null) {
                // 缓存查询结果
                cacheApplication(application);
            }

            requireNonNull(application, "应用不存在");
            return application;
        }, "查询应用失败");
    }

    @Override
    public Result<List<ApiApplication>> getApplicationsByOwner(Long ownerId) {
        return safeExecuteData(() -> {
            requireNonNull(ownerId, "用户ID不能为空");

            LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiApplication::getOwnerId, ownerId)
                    .orderByDesc(ApiApplication::getCreateTime);

            List<ApiApplication> applications = applicationMapper.selectList(wrapper);
            return applications != null ? applications : new ArrayList<>();
        }, "查询应用列表失败");
    }

    @Override
    public Result<Page<ApiApplication>> getApplicationsPage(int pageNum, int pageSize,
                                                            Integer appStatus, String keyword) {
        return safeExecuteData(() -> {
            requireCondition(pageNum, num -> num > 0, "页码必须大于0");
            requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

            Page<ApiApplication> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();

            // 状态过滤
            if (appStatus != null) {
                wrapper.eq(ApiApplication::getAppStatus, appStatus);
            }

            // 关键词搜索
            if (isNotBlank(keyword)) {
                wrapper.and(w -> w.like(ApiApplication::getAppName, keyword)
                        .or()
                        .like(ApiApplication::getAppCode, keyword));
            }

            wrapper.orderByDesc(ApiApplication::getCreateTime);

            return applicationMapper.selectPage(page, wrapper);
        }, "查询应用分页列表失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateIpWhitelist(Long appId, String ipWhitelist) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");

            // 验证IP白名单格式
            if (isNotBlank(ipWhitelist)) {
                JSONArray ipArray = JSONUtil.parseArray(ipWhitelist);
                for (Object ip : ipArray) {
                    String ipStr = ip.toString();
                    if (!isValidIp(ipStr) && !isValidCIDR(ipStr)) {
                        throw new RuntimeException("IP白名单格式不正确: " + ipStr);
                    }
                }
            }

            ApiApplication application = new ApiApplication();
            application.setId(appId);
            application.setIpWhitelist(ipWhitelist);

            int updated = applicationMapper.updateById(application);
            requireCondition(updated, count -> count > 0, "更新IP白名单失败");

            // 清除缓存,下次查询时重新加载
            ApiApplication app = applicationMapper.selectById(appId);
            if (app != null) {
                clearApplicationCache(app.getAppCode());
            }

            logInfo("更新应用IP白名单成功: appId={}", appId);
        }, "更新IP白名单失败");
    }

    @Override
    public Result<Boolean> validateIpWhitelist(Long appId, String clientIp) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonBlank(clientIp, "客户端IP不能为空");

            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            // 如果没有配置白名单,默认允许所有IP
            if (isBlank(application.getIpWhitelist())) {
                return true;
            }

            // 解析白名单
            JSONArray ipArray = JSONUtil.parseArray(application.getIpWhitelist());
            for (Object ip : ipArray) {
                String ipStr = ip.toString();
                // 支持精确匹配和CIDR格式
                if (ipStr.equals(clientIp) || isIpInCIDR(clientIp, ipStr)) {
                    return true;
                }
            }

            logWarn("IP不在白名单中: appId={}, clientIp={}", appId, clientIp);
            return false;
        }, "验证IP白名单失败");
    }

    @Override
    public Result<Map<String, Object>> getApplicationStatistics(Long appId, int days) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

            Map<String, Object> stats = new HashMap<>();

            // 获取应用基本信息
            ApiApplication application = applicationMapper.selectById(appId);
            requireNonNull(application, "应用不存在");

            stats.put("app_id", appId);
            stats.put("app_name", application.getAppName());
            stats.put("app_code", application.getAppCode());
            stats.put("app_status", application.getAppStatus());

            // 统计API密钥数量
            LambdaQueryWrapper<ApiKey> keyWrapper = new LambdaQueryWrapper<>();
            keyWrapper.eq(ApiKey::getAppId, appId);
            long totalKeys = apiKeyMapper.selectCount(keyWrapper);
            stats.put("total_keys", totalKeys);

            // 统计启用的密钥数量
            keyWrapper.eq(ApiKey::getKeyStatus, 1);
            long activeKeys = apiKeyMapper.selectCount(keyWrapper);
            stats.put("active_keys", activeKeys);

            // 从ApiCallLogService获取API调用统计数据
            Result<Map<String, Object>> callStats = apiCallLogService.getAppCallStatistics(appId, days);
            if (callStats.isSuccess() && callStats.getData() != null) {
                Map<String, Object> callData = callStats.getData();
                stats.put("total_requests", callData.getOrDefault("total_requests", 0));
                stats.put("success_requests", callData.getOrDefault("success_requests", 0));
                stats.put("failed_requests", callData.getOrDefault("failed_requests", 0));
                stats.put("success_rate", callData.getOrDefault("success_rate", 0.0));
                stats.put("avg_response_time", callData.getOrDefault("avg_response_time", 0));
                stats.put("max_response_time", callData.getOrDefault("max_response_time", 0));
                stats.put("total_response_size", callData.getOrDefault("total_response_size", 0L));
            } else {
                // 如果获取失败，返回默认值
                stats.put("total_requests", 0);
                stats.put("success_requests", 0);
                stats.put("failed_requests", 0);
                stats.put("success_rate", 0.0);
                stats.put("avg_response_time", 0);
                stats.put("max_response_time", 0);
                stats.put("total_response_size", 0L);
            }

            stats.put("stat_days", days);
            stats.put("stat_time", LocalDateTime.now());

            return stats;
        }, "获取应用统计信息失败");
    }

    @Override
    public Result<List<ApiApplication>> getPendingApplications() {
        return safeExecuteData(() -> {
            LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiApplication::getAppStatus, 0) // 待审核状态
                    .orderByAsc(ApiApplication::getCreateTime);

            List<ApiApplication> applications = applicationMapper.selectList(wrapper);
            return applications != null ? applications : new ArrayList<>();
        }, "查询待审核应用失败");
    }

    @Override
    public Result<Boolean> validateCallbackUrls(String callbackUrls) {
        return safeExecuteData(() -> {
            if (isBlank(callbackUrls)) {
                return true;
            }

            String[] urls = callbackUrls.split(",");
            for (String urlStr : urls) {
                urlStr = urlStr.trim();
                if (isBlank(urlStr)) {
                    continue;
                }

                try {
                    URL url = new URL(urlStr);
                    // 检查协议是否为http或https
                    String protocol = url.getProtocol();
                    if (!"http".equals(protocol) && !"https".equals(protocol)) {
                        logWarn("回调URL协议不正确: {}", urlStr);
                        return false;
                    }

                    // 检查是否有主机名
                    if (isBlank(url.getHost())) {
                        logWarn("回调URL缺少主机名: {}", urlStr);
                        return false;
                    }
                } catch (Exception e) {
                    logWarn("回调URL格式不正确: {}", urlStr);
                    return false;
                }
            }

            return true;
        }, "验证回调URL失败");
    }

    /**
     * 判断IP是否在CIDR范围内
     * 修复了位移越界的bug
     *
     * @param ip   IP地址
     * @param cidr CIDR格式IP
     * @return 是否在范围内
     */
    private boolean isIpInCIDR(String ip, String cidr) {
        if (!isValidCIDR(cidr)) {
            return false;
        }

        try {
            String[] parts = cidr.split("/");
            String networkIp = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            // 处理边界情况
            if (prefixLength == 0) {
                // /0 表示所有IP地址
                return true;
            }
            if (prefixLength == 32) {
                // /32 表示精确匹配
                return ip.equals(networkIp);
            }

            long ipLong = ipToLong(ip);
            long networkLong = ipToLong(networkIp);

            // 修复位移bug：确保位移操作在合法范围内
            long mask = (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;

            return (ipLong & mask) == (networkLong & mask);
        } catch (Exception e) {
            logError("判断IP是否在CIDR范围内失败", e);
            return false;
        }
    }

    /**
     * IP地址转换为长整型
     * 修复了符号位问题，使用无符号长整型
     *
     * @param ip IP地址
     * @return 长整型（无符号）
     */
    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) | (Long.parseLong(parts[i]) & 0xFF);
        }
        return result & 0xFFFFFFFFL;  // 确保返回无符号值
    }

    /**
     * 验证IP地址格式
     *
     * @param ip IP地址
     * @return 是否有效
     */
    private boolean isValidIp(String ip) {
        return IP_PATTERN.matcher(ip).matches();
    }

    /**
     * 验证CIDR格式
     *
     * @param cidr CIDR格式IP
     * @return 是否有效
     */
    private boolean isValidCIDR(String cidr) {
        return CIDR_PATTERN.matcher(cidr).matches();
    }

    /**
     * 从缓存获取应用信息
     *
     * @param appCode 应用标识码
     * @return 应用实体
     */
    private ApiApplication getApplicationFromCache(String appCode) {
        try {
            String cacheKey = APP_CACHE_PREFIX + appCode;
            Map<Object, Object> cachedData = redisTemplate.opsForHash().entries(cacheKey);

            if (!cachedData.isEmpty()) {
                ApiApplication application = new ApiApplication();
                application.setId(Long.parseLong((String) cachedData.get("id")));
                application.setAppName((String) cachedData.get("app_name"));
                application.setAppCode((String) cachedData.get("app_code"));
                application.setOwnerId(Long.parseLong((String) cachedData.get("owner_id")));
                application.setAppType(Integer.parseInt((String) cachedData.get("app_type")));
                application.setAppStatus(Integer.parseInt((String) cachedData.get("app_status")));
                if (cachedData.containsKey("ip_whitelist")) {
                    application.setIpWhitelist((String) cachedData.get("ip_whitelist"));
                }
                if (cachedData.containsKey("callback_url")) {
                    application.setCallbackUrl((String) cachedData.get("callback_url"));
                }
                return application;
            }
        } catch (Exception e) {
            logError("从缓存获取应用信息失败", e);
        }
        return null;
    }

    /**
     * 删除应用的所有API密钥
     *
     * @param appId 应用ID
     */
    private void deleteAllKeysForApp(Long appId) {
        try {
            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getAppId, appId);

            int deleted = apiKeyMapper.delete(wrapper);
            logInfo("删除应用的所有密钥: appId={}, count={}", appId, deleted);
        } catch (Exception e) {
            logError("删除应用密钥失败", e);
        }
    }

    /**
     * 禁用应用的所有API密钥
     *
     * @param appId 应用ID
     */
    private void disableAllKeysForApp(Long appId) {
        try {
            LambdaQueryWrapper<ApiKey> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiKey::getAppId, appId)
                    .eq(ApiKey::getKeyStatus, 1);

            List<ApiKey> keys = apiKeyMapper.selectList(wrapper);
            for (ApiKey key : keys) {
                key.setKeyStatus(0);
                apiKeyMapper.updateById(key);
            }

            logInfo("禁用应用的所有密钥: appId={}, count={}", appId, keys.size());
        } catch (Exception e) {
            logError("禁用应用密钥失败", e);
        }
    }

    /**
     * 清除应用缓存
     *
     * @param appCode 应用标识码
     */
    private void clearApplicationCache(String appCode) {
        try {
            String cacheKey = APP_CACHE_PREFIX + appCode;
            redisTemplate.delete(cacheKey);
        } catch (Exception e) {
            logError("清除应用缓存失败", e);
        }
    }

    /**
     * 生成应用标识码
     * 格式: app_ + 16位随机字符串
     *
     * @return 应用标识码
     */
    private String generateAppCode() {
        return "app_" + RandomUtil.randomString(16).toLowerCase();
    }

    /**
     * 检查应用标识码是否已存在
     *
     * @param appCode 应用标识码
     * @return 是否存在
     */
    private boolean isAppCodeExists(String appCode) {
        LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiApplication::getAppCode, appCode);
        return applicationMapper.selectCount(wrapper) > 0;
    }

    /**
     * 缓存应用信息
     *
     * @param application 应用实体
     */
    private void cacheApplication(ApiApplication application) {
        try {
            String cacheKey = APP_CACHE_PREFIX + application.getAppCode();
            Map<String, String> appInfo = new HashMap<>();
            appInfo.put("id", application.getId().toString());
            appInfo.put("app_name", application.getAppName());
            appInfo.put("app_code", application.getAppCode());
            appInfo.put("owner_id", application.getOwnerId().toString());
            appInfo.put("app_type", application.getAppType().toString());
            appInfo.put("app_status", application.getAppStatus().toString());
            if (isNotBlank(application.getIpWhitelist())) {
                appInfo.put("ip_whitelist", application.getIpWhitelist());
            }
            if (isNotBlank(application.getCallbackUrl())) {
                appInfo.put("callback_url", application.getCallbackUrl());
            }

            redisTemplate.opsForHash().putAll(cacheKey, appInfo);
            // 设置缓存过期时间为24小时
            redisTemplate.expire(cacheKey, 24, TimeUnit.HOURS);
        } catch (Exception e) {
            logError("缓存应用信息失败", e);
        }
    }
}
