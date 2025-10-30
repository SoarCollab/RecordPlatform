package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.constant.ApiGatewayConstants;
import cn.flying.identity.dto.apigateway.ApiApplication;
import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.apigateway.ApiApplicationMapper;
import cn.flying.identity.mapper.apigateway.ApiKeyMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiApplicationService;
import cn.flying.identity.service.apigateway.ApiCallLogService;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.util.JsonUtils;
import cn.flying.platformapi.constant.ResultEnum;
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
    public Map<String, Object> registerApplication(String appName, String appDescription,
                                                   Long ownerId, Integer appType,
                                                   String appWebsite, String callbackUrl) {
        requireNonBlank(appName, "应用名称不能为空");
        requireNonNull(ownerId, "所属用户ID不能为空");
        requireNonNull(appType, "应用类型不能为空");
        requireCondition(appType, ApiGatewayConstants.AppType::isValid, "应用类型必须在1-4之间");

        if (isNotBlank(callbackUrl) && !validateCallbackUrls(callbackUrl)) {
            throw businessException(ResultEnum.PARAM_IS_INVALID, "回调URL格式不正确");
        }

        String appCode = generateUniqueAppCode();

        ApiApplication application = new ApiApplication();
        application.setId(IdUtils.nextEntityId());
        application.setAppName(appName);
        application.setAppCode(appCode);
        application.setAppDescription(appDescription);
        application.setOwnerId(ownerId);
        application.setAppType(appType);
        application.setAppStatus(ApiGatewayConstants.AppStatus.PENDING);
        application.setAppWebsite(appWebsite);
        application.setCallbackUrl(callbackUrl);

        int inserted = applicationMapper.insert(application);
        if (inserted <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "注册应用失败");
        }

        cacheApplication(application);

        Map<String, Object> result = new HashMap<>();
        result.put("app_id", application.getId());
        result.put("app_code", appCode);
        result.put("app_name", appName);
        result.put("app_status", ApiGatewayConstants.AppStatus.PENDING);
        result.put("create_time", application.getCreateTime());
        result.put("message", "应用注册成功,等待管理员审核");

        logInfo("注册API应用成功: appId={}, appCode={}, ownerId={}", application.getId(), appCode, ownerId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveApplication(Long appId, boolean approved, Long approveBy, String rejectReason) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(approveBy, "审核人ID不能为空");

        if (!approved && isBlank(rejectReason)) {
            throw businessException(ResultEnum.PARAM_IS_INVALID, "拒绝审核时必须提供原因");
        }

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        if (application.getAppStatus() != ApiGatewayConstants.AppStatus.PENDING) {
            throw businessException(ResultEnum.OPERATION_FAILED, "应用当前状态无法审核");
        }

        application.setAppStatus(approved ? ApiGatewayConstants.AppStatus.ENABLED : ApiGatewayConstants.AppStatus.REJECTED);
        application.setApproveBy(approveBy);
        application.setApproveTime(LocalDateTime.now());

        int updated = applicationMapper.updateById(application);
        if (updated <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "审核应用失败");
        }

        if (approved) {
            cacheApplication(application);
        } else {
            clearApplicationCache(application.getAppCode());
        }

        logInfo("审核API应用: appId={}, approved={}, approveBy={}", appId, approved, approveBy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableApplication(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        application.setAppStatus(ApiGatewayConstants.AppStatus.ENABLED);
        int updated = applicationMapper.updateById(application);
        if (updated <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "启用应用失败");
        }

        cacheApplication(application);
        logInfo("启用API应用成功: appId={}", appId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableApplication(Long appId, String reason) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonBlank(reason, "禁用原因不能为空");

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        application.setAppStatus(ApiGatewayConstants.AppStatus.DISABLED);
        int updated = applicationMapper.updateById(application);
        if (updated <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "禁用应用失败");
        }

        clearApplicationCache(application.getAppCode());
        disableAllKeysForApp(appId);

        logInfo("禁用API应用成功: appId={}, reason={}", appId, reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteApplication(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        int deleted = applicationMapper.deleteById(appId);
        if (deleted <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "删除应用失败");
        }

        clearApplicationCache(application.getAppCode());
        deleteAllKeysForApp(appId);

        logInfo("删除API应用成功: appId={}", appId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateApplication(ApiApplication application) {
        requireNonNull(application, "应用信息不能为空");
        requireNonNull(application.getId(), "应用ID不能为空");

        if (isNotBlank(application.getCallbackUrl()) && !validateCallbackUrls(application.getCallbackUrl())) {
            throw businessException(ResultEnum.PARAM_IS_INVALID, "回调URL格式不正确");
        }

        int updated = applicationMapper.updateById(application);
        if (updated <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "更新应用失败");
        }

        cacheApplication(application);
        logInfo("更新API应用成功: appId={}", application.getId());
    }

    @Override
    public ApiApplication getApplicationById(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");
        return application;
    }

    @Override
    public ApiApplication getApplicationByCode(String appCode) {
        requireNonBlank(appCode, "应用标识码不能为空");

        ApiApplication cachedApp = getApplicationFromCache(appCode);
        if (cachedApp != null) {
            return cachedApp;
        }

        LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiApplication::getAppCode, appCode);
        ApiApplication application = applicationMapper.selectOne(wrapper);
        requireNonNull(application, "应用不存在");

        cacheApplication(application);
        return application;
    }

    @Override
    public List<ApiApplication> getApplicationsByOwner(Long ownerId) {
        requireNonNull(ownerId, "用户ID不能为空");

        LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiApplication::getOwnerId, ownerId)
                .orderByDesc(ApiApplication::getCreateTime);

        List<ApiApplication> applications = applicationMapper.selectList(wrapper);
        return applications != null ? applications : new ArrayList<>();
    }

    @Override
    public Page<ApiApplication> getApplicationsPage(int pageNum, int pageSize,
                                                    Integer appStatus, String keyword) {
        requireCondition(pageNum, num -> num > 0, "页码必须大于0");
        requireCondition(pageSize, size -> size > 0 && size <= 100, "每页大小必须在1-100之间");

        Page<ApiApplication> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();

        if (appStatus != null) {
            wrapper.eq(ApiApplication::getAppStatus, appStatus);
        }

        if (isNotBlank(keyword)) {
            wrapper.and(w -> w.like(ApiApplication::getAppName, keyword)
                    .or()
                    .like(ApiApplication::getAppCode, keyword));
        }

        wrapper.orderByDesc(ApiApplication::getCreateTime);
        return applicationMapper.selectPage(page, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateIpWhitelist(Long appId, String ipWhitelist) {
        requireNonNull(appId, "应用ID不能为空");

        if (isNotBlank(ipWhitelist)) {
            JSONArray ipArray = JSONUtil.parseArray(ipWhitelist);
            for (Object ip : ipArray) {
                String ipStr = ip.toString();
                if (!isValidIp(ipStr) && !isValidCIDR(ipStr)) {
                    throw businessException(ResultEnum.PARAM_IS_INVALID, "IP白名单格式不正确: " + ipStr);
                }
            }
        }

        ApiApplication application = new ApiApplication();
        application.setId(appId);
        application.setIpWhitelist(ipWhitelist);

        int updated = applicationMapper.updateById(application);
        if (updated <= 0) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "更新IP白名单失败");
        }

        ApiApplication app = applicationMapper.selectById(appId);
        if (app != null) {
            clearApplicationCache(app.getAppCode());
        }

        logInfo("更新应用IP白名单成功: appId={}", appId);
    }

    @Override
    public boolean validateIpWhitelist(Long appId, String clientIp) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonBlank(clientIp, "客户端IP不能为空");

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        if (isBlank(application.getIpWhitelist())) {
            return true;
        }

        JSONArray ipArray = JSONUtil.parseArray(application.getIpWhitelist());
        for (Object ip : ipArray) {
            String ipStr = ip.toString();
            if (ipStr.equals(clientIp) || isIpInCIDR(clientIp, ipStr)) {
                return true;
            }
        }

        logWarn("IP不在白名单中: appId={}, clientIp={}", appId, clientIp);
        return false;
    }

    @Override
    public Map<String, Object> getApplicationStatistics(Long appId, int days) {
        requireNonNull(appId, "应用ID不能为空");
        requireCondition(days, d -> d > 0 && d <= 90, "统计天数必须在1-90之间");

        Map<String, Object> stats = new HashMap<>();

        ApiApplication application = applicationMapper.selectById(appId);
        requireNonNull(application, "应用不存在");

        stats.put("app_id", appId);
        stats.put("app_name", application.getAppName());
        stats.put("app_code", application.getAppCode());
        stats.put("app_status", application.getAppStatus());

        LambdaQueryWrapper<ApiKey> keyWrapper = new LambdaQueryWrapper<>();
        keyWrapper.eq(ApiKey::getAppId, appId);
        long totalKeys = apiKeyMapper.selectCount(keyWrapper);
        stats.put("total_keys", totalKeys);

        keyWrapper.eq(ApiKey::getKeyStatus, ApiGatewayConstants.KeyStatus.ENABLED);
        long activeKeys = apiKeyMapper.selectCount(keyWrapper);
        stats.put("active_keys", activeKeys);

        Map<String, Object> callData = new HashMap<>();
        try {
            Map<String, Object> statsData = apiCallLogService.getAppCallStatistics(appId, days);
            if (statsData != null) {
                callData.putAll(statsData);
            }
        } catch (BusinessException ex) {
            logWarn("获取应用调用统计失败: appId={}, days={}, msg={}", appId, days, ex.getMessage());
        } catch (Exception ex) {
            logWarn("获取应用调用统计异常: appId={}, days={}, msg={}", appId, days, ex.getMessage());
        }
        stats.put("total_requests", callData.getOrDefault("total_requests", 0));
        stats.put("success_requests", callData.getOrDefault("success_requests", 0));
        stats.put("failed_requests", callData.getOrDefault("failed_requests", 0));
        stats.put("success_rate", callData.getOrDefault("success_rate", 0.0));
        stats.put("avg_response_time", callData.getOrDefault("avg_response_time", 0));
        stats.put("max_response_time", callData.getOrDefault("max_response_time", 0));
        stats.put("total_response_size", callData.getOrDefault("total_response_size", 0L));

        stats.put("stat_days", days);
        stats.put("stat_time", LocalDateTime.now());

        cacheApplicationStatistics(appId, stats);
        return stats;
    }

    @Override
    public List<ApiApplication> getPendingApplications() {
        LambdaQueryWrapper<ApiApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiApplication::getAppStatus, ApiGatewayConstants.AppStatus.PENDING)
                .orderByAsc(ApiApplication::getCreateTime);

        List<ApiApplication> applications = applicationMapper.selectList(wrapper);
        return applications != null ? applications : new ArrayList<>();
    }

    @Override
    public boolean validateCallbackUrls(String callbackUrls) {
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
                String protocol = url.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) {
                    logWarn("回调URL协议不正确: {}", urlStr);
                    return false;
                }

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
     * 生成唯一的应用標識碼
     */
    private String generateUniqueAppCode() {
        String appCode = generateAppCode();
        int retryCount = 0;

        while (isAppCodeExists(appCode)
                && retryCount++ < ApiGatewayConstants.SystemConfig.MAX_APP_CODE_RETRY) {
            appCode = generateAppCode();
        }

        if (retryCount >= ApiGatewayConstants.SystemConfig.MAX_APP_CODE_RETRY) {
            throw businessException(ResultEnum.SYSTEM_ERROR, "无法生成唯一的应用标识码，请稍后重试");
        }
        return appCode;
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

    /**
     * 緩存應用統計資料
     */
    private void cacheApplicationStatistics(Long appId, Map<String, Object> stats) {
        try {
            String cacheKey = APP_STATS_PREFIX + appId;
            redisTemplate.opsForValue().set(cacheKey, JsonUtils.toJson(stats), 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            logWarn("缓存应用统计信息失败: appId={}", appId, e);
        }
    }
}
