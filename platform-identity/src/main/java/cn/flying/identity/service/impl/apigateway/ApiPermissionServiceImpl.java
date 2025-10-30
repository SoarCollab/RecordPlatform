package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiInterface;
import cn.flying.identity.dto.apigateway.ApiPermission;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.mapper.apigateway.ApiInterfaceMapper;
import cn.flying.identity.mapper.apigateway.ApiPermissionMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiPermissionService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * API权限控制服务实现类
 * 提供细粒度的API访问权限管理
 *
 * 核心功能:
 * 1. 权限授予与撤销
 * 2. 权限验证(支持缓存加速)
 * 3. 权限过期管理
 * 4. 批量权限操作
 */
@Slf4j
@Service
public class ApiPermissionServiceImpl extends BaseService implements ApiPermissionService {

    @Resource
    private ApiPermissionMapper permissionMapper;

    @Resource
    private ApiInterfaceMapper interfaceMapper;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    /**
     * Redis键前缀
     */
    private static final String PERMISSION_CACHE_PREFIX = "api:permission:";
    private static final String APP_INTERFACES_PREFIX = "api:app:interfaces:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantPermission(Long appId, Long interfaceId, Long grantBy, Integer expireDays) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(interfaceId, "接口ID不能为空");
        requireNonNull(grantBy, "授权人ID不能为空");

        ApiInterface apiInterface = interfaceMapper.selectById(interfaceId);
        requireNonNull(apiInterface, ResultEnum.RESULT_DATA_NONE, "接口不存在");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .eq(ApiPermission::getInterfaceId, interfaceId);

        ApiPermission existingPermission = permissionMapper.selectOne(wrapper);

        if (existingPermission != null) {
            existingPermission.setPermissionStatus(1);
            existingPermission.setGrantBy(grantBy);
            existingPermission.setGrantTime(LocalDateTime.now());

            if (expireDays != null && expireDays > 0) {
                existingPermission.setExpireTime(LocalDateTime.now().plusDays(expireDays));
            } else {
                existingPermission.setExpireTime(null);
            }

            permissionMapper.updateById(existingPermission);
            logInfo("更新API权限: appId={}, interfaceId={}, permissionId={}",
                    appId, interfaceId, existingPermission.getId());
        } else {
            ApiPermission permission = new ApiPermission();
            permission.setId(IdUtils.nextEntityId());
            permission.setAppId(appId);
            permission.setInterfaceId(interfaceId);
            permission.setPermissionStatus(1);
            permission.setGrantBy(grantBy);
            permission.setGrantTime(LocalDateTime.now());

            if (expireDays != null && expireDays > 0) {
                permission.setExpireTime(LocalDateTime.now().plusDays(expireDays));
            }

            int inserted = permissionMapper.insert(permission);
            requireCondition(inserted, count -> count > 0, ResultEnum.SYSTEM_ERROR, "授予权限失败");

            logInfo("授予API权限: appId={}, interfaceId={}, permissionId={}",
                    appId, interfaceId, permission.getId());
        }

        syncPermissionsToCache(appId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> grantBatchPermissions(Long appId, List<Long> interfaceIds,
                                                     Long grantBy, Integer expireDays) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonEmpty(interfaceIds, "接口ID列表不能为空");
        requireNonNull(grantBy, "授权人ID不能为空");

        int successCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long interfaceId : interfaceIds) {
            try {
                grantPermission(appId, interfaceId, grantBy, expireDays);
                successCount++;
            } catch (BusinessException ex) {
                failedCount++;
                errors.add("接口ID " + interfaceId + " 授权失败：" + ex.getMessage());
            } catch (Exception ex) {
                failedCount++;
                errors.add("接口ID " + interfaceId + " 授权异常：" + ex.getMessage());
                logError("批量授权异常: interfaceId={}", interfaceId, ex);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", interfaceIds.size());
        result.put("success", successCount);
        result.put("failed", failedCount);
        result.put("errors", errors);

        logInfo("批量授予权限完成: appId={}, total={}, success={}, failed={}",
                appId, interfaceIds.size(), successCount, failedCount);

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokePermission(Long appId, Long interfaceId) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(interfaceId, "接口ID不能为空");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .eq(ApiPermission::getInterfaceId, interfaceId);

        int deleted = permissionMapper.delete(wrapper);
        requireCondition(deleted, count -> count > 0, ResultEnum.RESULT_DATA_NONE, "权限不存在");

        clearPermissionCache(appId, interfaceId);
        logInfo("撤销API权限: appId={}, interfaceId={}", appId, interfaceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enablePermission(Long permissionId) {
        requireNonNull(permissionId, "权限ID不能为空");

        ApiPermission permission = permissionMapper.selectById(permissionId);
        requireNonNull(permission, ResultEnum.RESULT_DATA_NONE, "权限不存在");

        permission.setPermissionStatus(1);
        int updated = permissionMapper.updateById(permission);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "启用权限失败");

        syncPermissionsToCache(permission.getAppId());
        logInfo("启用API权限: permissionId={}", permissionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disablePermission(Long permissionId) {
        requireNonNull(permissionId, "权限ID不能为空");

        ApiPermission permission = permissionMapper.selectById(permissionId);
        requireNonNull(permission, ResultEnum.RESULT_DATA_NONE, "权限不存在");

        permission.setPermissionStatus(0);
        int updated = permissionMapper.updateById(permission);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "禁用权限失败");

        clearPermissionCache(permission.getAppId(), permission.getInterfaceId());
        logInfo("禁用API权限: permissionId={}", permissionId);
    }

    @Override
    public boolean hasPermission(Long appId, Long interfaceId) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonNull(interfaceId, "接口ID不能为空");

        Boolean cachedResult = checkPermissionFromCache(appId, interfaceId);
        if (cachedResult != null) {
            return cachedResult;
        }

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .eq(ApiPermission::getInterfaceId, interfaceId)
                .eq(ApiPermission::getPermissionStatus, 1);

        ApiPermission permission = permissionMapper.selectOne(wrapper);

        if (permission == null) {
            cachePermissionResult(appId, interfaceId, false, 60);
            return false;
        }

        if (permission.getExpireTime() != null && LocalDateTime.now().isAfter(permission.getExpireTime())) {
            cachePermissionResult(appId, interfaceId, false, 60);
            return false;
        }

        cachePermissionResult(appId, interfaceId, true, 300);
        return true;
    }

    @Override
    public boolean hasPermissionByPath(Long appId, String interfacePath, String interfaceMethod) {
        requireNonNull(appId, "应用ID不能为空");
        requireNonBlank(interfacePath, "接口路径不能为空");
        requireNonBlank(interfaceMethod, "接口方法不能为空");

        LambdaQueryWrapper<ApiInterface> interfaceWrapper = new LambdaQueryWrapper<>();
        interfaceWrapper.eq(ApiInterface::getInterfacePath, interfacePath)
                .eq(ApiInterface::getInterfaceMethod, interfaceMethod)
                .eq(ApiInterface::getInterfaceStatus, 1);

        ApiInterface apiInterface = interfaceMapper.selectOne(interfaceWrapper);

        if (apiInterface == null) {
            logWarn("接口不存在或已下线: path={}, method={}", interfacePath, interfaceMethod);
            return false;
        }

        return hasPermission(appId, apiInterface.getId());
    }

    @Override
    public List<ApiPermission> getPermissionsByApp(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .orderByDesc(ApiPermission::getGrantTime);

        List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
        return permissions != null ? permissions : new ArrayList<>();
    }

    @Override
    public List<ApiPermission> getPermissionsByInterface(Long interfaceId) {
        requireNonNull(interfaceId, "接口ID不能为空");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getInterfaceId, interfaceId)
                .orderByDesc(ApiPermission::getGrantTime);

        List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
        return permissions != null ? permissions : new ArrayList<>();
    }

    @Override
    public List<Map<String, Object>> getAccessibleInterfaces(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .eq(ApiPermission::getPermissionStatus, 1);

        List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
        if (permissions == null || permissions.isEmpty()) {
            return new ArrayList<>();
        }

        LocalDateTime now = LocalDateTime.now();
        List<Long> interfaceIds = permissions.stream()
                .filter(p -> p.getExpireTime() == null || now.isBefore(p.getExpireTime()))
                .map(ApiPermission::getInterfaceId)
                .collect(Collectors.toList());

        if (interfaceIds.isEmpty()) {
            return new ArrayList<>();
        }

        List<ApiInterface> interfaces = interfaceMapper.selectBatchIds(interfaceIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiInterface apiInterface : interfaces) {
            Map<String, Object> interfaceInfo = new HashMap<>();
            interfaceInfo.put("interface_id", apiInterface.getId());
            interfaceInfo.put("interface_name", apiInterface.getInterfaceName());
            interfaceInfo.put("interface_code", apiInterface.getInterfaceCode());
            interfaceInfo.put("interface_path", apiInterface.getInterfacePath());
            interfaceInfo.put("interface_method", apiInterface.getInterfaceMethod());
            interfaceInfo.put("interface_description", apiInterface.getInterfaceDescription());
            interfaceInfo.put("interface_category", apiInterface.getInterfaceCategory());
            interfaceInfo.put("rate_limit", apiInterface.getRateLimit());
            interfaceInfo.put("interface_status", apiInterface.getInterfaceStatus());

            permissions.stream()
                    .filter(p -> p.getInterfaceId().equals(apiInterface.getId()))
                    .findFirst()
                    .ifPresent(p -> {
                        interfaceInfo.put("permission_id", p.getId());
                        interfaceInfo.put("grant_time", p.getGrantTime());
                        interfaceInfo.put("expire_time", p.getExpireTime());
                    });

            result.add(interfaceInfo);
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void extendPermission(Long permissionId, int expireDays) {
        requireNonNull(permissionId, "权限ID不能为空");
        requireCondition(expireDays, days -> days > 0, "延长天数必须大于0");

        ApiPermission permission = permissionMapper.selectById(permissionId);
        requireNonNull(permission, ResultEnum.RESULT_DATA_NONE, "权限不存在");

        LocalDateTime newExpireTime;
        if (permission.getExpireTime() != null && LocalDateTime.now().isBefore(permission.getExpireTime())) {
            newExpireTime = permission.getExpireTime().plusDays(expireDays);
        } else {
            newExpireTime = LocalDateTime.now().plusDays(expireDays);
        }

        permission.setExpireTime(newExpireTime);
        int updated = permissionMapper.updateById(permission);
        requireCondition(updated, count -> count > 0, ResultEnum.SYSTEM_ERROR, "延长权限失败");

        syncPermissionsToCache(permission.getAppId());
        logInfo("延长API权限: permissionId={}, days={}, newExpireTime={}",
                permissionId, expireDays, newExpireTime);
    }

    @Override
    public List<ApiPermission> getExpiringPermissions(int days) {
        requireCondition(days, d -> d > 0, "提前天数必须大于0");

        LocalDateTime threshold = LocalDateTime.now().plusDays(days);

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getPermissionStatus, 1)
                .isNotNull(ApiPermission::getExpireTime)
                .le(ApiPermission::getExpireTime, threshold)
                .ge(ApiPermission::getExpireTime, LocalDateTime.now());

        List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
        return permissions != null ? permissions : new ArrayList<>();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredPermissions() {
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.isNotNull(ApiPermission::getExpireTime)
                .lt(ApiPermission::getExpireTime, now);

        List<ApiPermission> expiredPermissions = permissionMapper.selectList(wrapper);
        if (expiredPermissions == null || expiredPermissions.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (ApiPermission permission : expiredPermissions) {
            permission.setPermissionStatus(0);
            permissionMapper.updateById(permission);
            clearPermissionCache(permission.getAppId(), permission.getInterfaceId());
            count++;
        }

        logInfo("清理过期权限完成: count={}", count);
        return count;
    }

    @Override
    public void syncPermissionsToCache(Long appId) {
        requireNonNull(appId, "应用ID不能为空");

        LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiPermission::getAppId, appId)
                .eq(ApiPermission::getPermissionStatus, 1);

        List<ApiPermission> permissions = permissionMapper.selectList(wrapper);

        if (permissions == null || permissions.isEmpty()) {
            String cacheKey = APP_INTERFACES_PREFIX + appId;
            redisTemplate.delete(cacheKey);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Set<String> interfaceIds = permissions.stream()
                .filter(p -> p.getExpireTime() == null || now.isBefore(p.getExpireTime()))
                .map(p -> p.getInterfaceId().toString())
                .collect(Collectors.toSet());

        String cacheKey = APP_INTERFACES_PREFIX + appId;
        redisTemplate.delete(cacheKey);

        if (!interfaceIds.isEmpty()) {
            redisTemplate.opsForSet().add(cacheKey, interfaceIds.toArray(new String[0]));
            redisTemplate.expire(cacheKey, 1, TimeUnit.HOURS);
        }

        logInfo("同步应用权限到缓存: appId={}, count={}", appId, interfaceIds.size());
    }

    private Boolean checkPermissionFromCache(Long appId, Long interfaceId) {
        try {
            String cacheKey = APP_INTERFACES_PREFIX + appId;
            Boolean exists = redisTemplate.hasKey(cacheKey);

            if (!exists) {
                return null;
            }

            Boolean isMember = redisTemplate.opsForSet().isMember(cacheKey, interfaceId.toString());
            return Boolean.TRUE.equals(isMember);
        } catch (Exception e) {
            logError("从缓存检查权限失败", e);
            return null;
        }
    }

    private void cachePermissionResult(Long appId, Long interfaceId, boolean hasPermission, long expireSeconds) {
        try {
            String cacheKey = PERMISSION_CACHE_PREFIX + appId + ":" + interfaceId;
            redisTemplate.opsForValue().set(cacheKey, hasPermission ? "1" : "0", expireSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logError("缓存权限结果失败", e);
        }
    }

    private void clearPermissionCache(Long appId, Long interfaceId) {
        try {
            String cacheKey = PERMISSION_CACHE_PREFIX + appId + ":" + interfaceId;
            redisTemplate.delete(cacheKey);

            String appCacheKey = APP_INTERFACES_PREFIX + appId;
            redisTemplate.opsForSet().remove(appCacheKey, interfaceId.toString());
        } catch (Exception e) {
            logError("清除权限缓存失败", e);
        }
    }
}
