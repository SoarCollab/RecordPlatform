package cn.flying.identity.service.impl.apigateway;

import cn.flying.identity.dto.apigateway.ApiInterface;
import cn.flying.identity.dto.apigateway.ApiPermission;
import cn.flying.identity.mapper.apigateway.ApiInterfaceMapper;
import cn.flying.identity.mapper.apigateway.ApiPermissionMapper;
import cn.flying.identity.service.BaseService;
import cn.flying.identity.service.apigateway.ApiPermissionService;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
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
 * <p>
 * 核心功能:
 * 1. 权限授予与撤销
 * 2. 权限验证(支持缓存加速)
 * 3. 权限过期管理
 * 4. 批量权限操作
 *
 * @author 王贝强
 * @since 2025-10-11
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
    public Result<Void> grantPermission(Long appId, Long interfaceId, Long grantBy, Integer expireDays) {
        return safeExecuteAction(() -> {
            // 参数验证
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(interfaceId, "接口ID不能为空");
            requireNonNull(grantBy, "授权人ID不能为空");

            // 检查接口是否存在
            ApiInterface apiInterface = interfaceMapper.selectById(interfaceId);
            requireNonNull(apiInterface, "接口不存在");

            // 检查是否已存在权限
            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .eq(ApiPermission::getInterfaceId, interfaceId);

            ApiPermission existingPermission = permissionMapper.selectOne(wrapper);

            if (existingPermission != null) {
                // 更新已有权限
                existingPermission.setPermissionStatus(1);
                existingPermission.setGrantBy(grantBy);
                existingPermission.setGrantTime(LocalDateTime.now());

                if (expireDays != null && expireDays > 0) {
                    existingPermission.setExpireTime(LocalDateTime.now().plusDays(expireDays));
                } else {
                    existingPermission.setExpireTime(null); // 永久有效
                }

                permissionMapper.updateById(existingPermission);
                logInfo("更新API权限: appId={}, interfaceId={}, permissionId={}",
                        appId, interfaceId, existingPermission.getId());
            } else {
                // 创建新权限
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
                requireCondition(inserted, count -> count > 0, "授予权限失败");

                logInfo("授予API权限: appId={}, interfaceId={}, permissionId={}",
                        appId, interfaceId, permission.getId());
            }

            // 同步权限到缓存
            syncPermissionsToCache(appId);
        }, "授予权限失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> grantBatchPermissions(Long appId, List<Long> interfaceIds,
                                                              Long grantBy, Integer expireDays) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonEmpty(interfaceIds, "接口ID列表不能为空");
            requireNonNull(grantBy, "授权人ID不能为空");

            int successCount = 0;
            int failedCount = 0;
            List<String> errors = new ArrayList<>();

            for (Long interfaceId : interfaceIds) {
                try {
                    Result<Void> result = grantPermission(appId, interfaceId, grantBy, expireDays);
                    if (isSuccess(result)) {
                        successCount++;
                    } else {
                        failedCount++;
                        errors.add("接口ID " + interfaceId + " 授权失败");
                    }
                } catch (Exception e) {
                    failedCount++;
                    errors.add("接口ID " + interfaceId + " 授权异常: " + e.getMessage());
                    logError("批量授权异常: interfaceId={}", e, interfaceId);
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
        }, "批量授予权限失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> revokePermission(Long appId, Long interfaceId) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(interfaceId, "接口ID不能为空");

            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .eq(ApiPermission::getInterfaceId, interfaceId);

            int deleted = permissionMapper.delete(wrapper);
            requireCondition(deleted, count -> count > 0, "撤销权限失败,权限不存在");

            // 清除缓存
            clearPermissionCache(appId, interfaceId);

            logInfo("撤销API权限: appId={}, interfaceId={}", appId, interfaceId);
        }, "撤销权限失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> enablePermission(Long permissionId) {
        return safeExecuteAction(() -> {
            requireNonNull(permissionId, "权限ID不能为空");

            ApiPermission permission = permissionMapper.selectById(permissionId);
            requireNonNull(permission, "权限不存在");

            permission.setPermissionStatus(1);
            int updated = permissionMapper.updateById(permission);
            requireCondition(updated, count -> count > 0, "启用权限失败");

            // 同步缓存
            syncPermissionsToCache(permission.getAppId());

            logInfo("启用API权限: permissionId={}", permissionId);
        }, "启用权限失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> disablePermission(Long permissionId) {
        return safeExecuteAction(() -> {
            requireNonNull(permissionId, "权限ID不能为空");

            ApiPermission permission = permissionMapper.selectById(permissionId);
            requireNonNull(permission, "权限不存在");

            permission.setPermissionStatus(0);
            int updated = permissionMapper.updateById(permission);
            requireCondition(updated, count -> count > 0, "禁用权限失败");

            // 清除缓存
            clearPermissionCache(permission.getAppId(), permission.getInterfaceId());

            logInfo("禁用API权限: permissionId={}", permissionId);
        }, "禁用权限失败");
    }

    @Override
    public Result<Boolean> hasPermission(Long appId, Long interfaceId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonNull(interfaceId, "接口ID不能为空");

            // 先从缓存查询
            Boolean cachedResult = checkPermissionFromCache(appId, interfaceId);
            if (cachedResult != null) {
                return cachedResult;
            }

            // 缓存未命中,从数据库查询
            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .eq(ApiPermission::getInterfaceId, interfaceId)
                    .eq(ApiPermission::getPermissionStatus, 1);

            ApiPermission permission = permissionMapper.selectOne(wrapper);

            if (permission == null) {
                // 缓存否定结果(短时间)
                cachePermissionResult(appId, interfaceId, false, 60);
                return false;
            }

            // 检查是否过期
            if (permission.getExpireTime() != null && LocalDateTime.now().isAfter(permission.getExpireTime())) {
                // 权限已过期
                cachePermissionResult(appId, interfaceId, false, 60);
                return false;
            }

            // 缓存正向结果
            cachePermissionResult(appId, interfaceId, true, 300);
            return true;
        }, "检查权限失败");
    }

    @Override
    public Result<Boolean> hasPermissionByPath(Long appId, String interfacePath, String interfaceMethod) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");
            requireNonBlank(interfacePath, "接口路径不能为空");
            requireNonBlank(interfaceMethod, "接口方法不能为空");

            // 根据路径和方法查询接口
            LambdaQueryWrapper<ApiInterface> interfaceWrapper = new LambdaQueryWrapper<>();
            interfaceWrapper.eq(ApiInterface::getInterfacePath, interfacePath)
                    .eq(ApiInterface::getInterfaceMethod, interfaceMethod)
                    .eq(ApiInterface::getInterfaceStatus, 1); // 仅查询已上线的接口

            ApiInterface apiInterface = interfaceMapper.selectOne(interfaceWrapper);

            if (apiInterface == null) {
                logWarn("接口不存在或已下线: path={}, method={}", interfacePath, interfaceMethod);
                return false;
            }

            // 检查权限
            Result<Boolean> permissionResult = hasPermission(appId, apiInterface.getId());
            return isSuccess(permissionResult) && permissionResult.getData();
        }, "检查路径权限失败");
    }

    @Override
    public Result<List<ApiPermission>> getPermissionsByApp(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .orderByDesc(ApiPermission::getGrantTime);

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
            return permissions != null ? permissions : new ArrayList<>();
        }, "查询应用权限列表失败");
    }

    @Override
    public Result<List<ApiPermission>> getPermissionsByInterface(Long interfaceId) {
        return safeExecuteData(() -> {
            requireNonNull(interfaceId, "接口ID不能为空");

            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getInterfaceId, interfaceId)
                    .orderByDesc(ApiPermission::getGrantTime);

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
            return permissions != null ? permissions : new ArrayList<>();
        }, "查询接口权限列表失败");
    }

    @Override
    public Result<List<Map<String, Object>>> getAccessibleInterfaces(Long appId) {
        return safeExecuteData(() -> {
            requireNonNull(appId, "应用ID不能为空");

            // 查询应用的所有有效权限
            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .eq(ApiPermission::getPermissionStatus, 1);

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);

            if (permissions == null || permissions.isEmpty()) {
                return new ArrayList<>();
            }

            // 过滤掉已过期的权限
            LocalDateTime now = LocalDateTime.now();
            List<Long> interfaceIds = permissions.stream()
                    .filter(p -> p.getExpireTime() == null || now.isBefore(p.getExpireTime()))
                    .map(ApiPermission::getInterfaceId)
                    .collect(Collectors.toList());

            if (interfaceIds.isEmpty()) {
                return new ArrayList<>();
            }

            // 查询接口详情
            List<ApiInterface> interfaces = interfaceMapper.selectBatchIds(interfaceIds);

            // 组装结果
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

                // 查找对应的权限信息
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
        }, "查询可访问接口列表失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> extendPermission(Long permissionId, int expireDays) {
        return safeExecuteAction(() -> {
            requireNonNull(permissionId, "权限ID不能为空");
            requireCondition(expireDays, days -> days > 0, "延长天数必须大于0");

            ApiPermission permission = permissionMapper.selectById(permissionId);
            requireNonNull(permission, "权限不存在");

            // 计算新的过期时间
            LocalDateTime newExpireTime;
            if (permission.getExpireTime() != null && LocalDateTime.now().isBefore(permission.getExpireTime())) {
                // 在原过期时间基础上延长
                newExpireTime = permission.getExpireTime().plusDays(expireDays);
            } else {
                // 从当前时间开始计算
                newExpireTime = LocalDateTime.now().plusDays(expireDays);
            }

            permission.setExpireTime(newExpireTime);
            int updated = permissionMapper.updateById(permission);
            requireCondition(updated, count -> count > 0, "延长权限失败");

            // 同步缓存
            syncPermissionsToCache(permission.getAppId());

            logInfo("延长API权限: permissionId={}, days={}, newExpireTime={}",
                    permissionId, expireDays, newExpireTime);
        }, "延长权限失败");
    }

    @Override
    public Result<List<ApiPermission>> getExpiringPermissions(int days) {
        return safeExecuteData(() -> {
            requireCondition(days, d -> d > 0, "提前天数必须大于0");

            LocalDateTime threshold = LocalDateTime.now().plusDays(days);

            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getPermissionStatus, 1)
                    .isNotNull(ApiPermission::getExpireTime)
                    .le(ApiPermission::getExpireTime, threshold)
                    .ge(ApiPermission::getExpireTime, LocalDateTime.now());

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);
            return permissions != null ? permissions : new ArrayList<>();
        }, "查询即将过期权限失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Integer> cleanExpiredPermissions() {
        return safeExecuteData(() -> {
            LocalDateTime now = LocalDateTime.now();

            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.isNotNull(ApiPermission::getExpireTime)
                    .lt(ApiPermission::getExpireTime, now);

            List<ApiPermission> expiredPermissions = permissionMapper.selectList(wrapper);

            if (expiredPermissions == null || expiredPermissions.isEmpty()) {
                return 0;
            }

            // 禁用过期权限
            int count = 0;
            for (ApiPermission permission : expiredPermissions) {
                permission.setPermissionStatus(0);
                permissionMapper.updateById(permission);

                // 清除缓存
                clearPermissionCache(permission.getAppId(), permission.getInterfaceId());
                count++;
            }

            logInfo("清理过期权限完成: count={}", count);
            return count;
        }, "清理过期权限失败");
    }

    @Override
    public Result<Void> syncPermissionsToCache(Long appId) {
        return safeExecuteAction(() -> {
            requireNonNull(appId, "应用ID不能为空");

            // 查询应用的所有有效权限
            LambdaQueryWrapper<ApiPermission> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ApiPermission::getAppId, appId)
                    .eq(ApiPermission::getPermissionStatus, 1);

            List<ApiPermission> permissions = permissionMapper.selectList(wrapper);

            if (permissions == null || permissions.isEmpty()) {
                // 清除缓存
                String cacheKey = APP_INTERFACES_PREFIX + appId;
                redisTemplate.delete(cacheKey);
                return;
            }

            // 过滤掉已过期的权限
            LocalDateTime now = LocalDateTime.now();
            Set<String> interfaceIds = permissions.stream()
                    .filter(p -> p.getExpireTime() == null || now.isBefore(p.getExpireTime()))
                    .map(p -> p.getInterfaceId().toString())
                    .collect(Collectors.toSet());

            // 存储到Redis Set
            String cacheKey = APP_INTERFACES_PREFIX + appId;
            redisTemplate.delete(cacheKey); // 先清除旧数据

            if (!interfaceIds.isEmpty()) {
                redisTemplate.opsForSet().add(cacheKey, interfaceIds.toArray(new String[0]));
                // 设置过期时间为1小时
                redisTemplate.expire(cacheKey, 1, TimeUnit.HOURS);
            }

            logInfo("同步应用权限到缓存: appId={}, count={}", appId, interfaceIds.size());
        }, "同步权限到缓存失败");
    }

    /**
     * 从缓存检查权限
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @return 是否有权限,null表示缓存未命中
     */
    private Boolean checkPermissionFromCache(Long appId, Long interfaceId) {
        try {
            String cacheKey = APP_INTERFACES_PREFIX + appId;
            Boolean exists = redisTemplate.hasKey(cacheKey);

            if (Boolean.FALSE.equals(exists)) {
                return null; // 缓存未命中
            }

            Boolean isMember = redisTemplate.opsForSet().isMember(cacheKey, interfaceId.toString());
            return Boolean.TRUE.equals(isMember);
        } catch (Exception e) {
            logError("从缓存检查权限失败", e);
            return null;
        }
    }

    /**
     * 缓存权限验证结果
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     * @param hasPermission 是否有权限
     * @param expireSeconds 过期时间(秒)
     */
    private void cachePermissionResult(Long appId, Long interfaceId, boolean hasPermission, long expireSeconds) {
        try {
            String cacheKey = PERMISSION_CACHE_PREFIX + appId + ":" + interfaceId;
            redisTemplate.opsForValue().set(cacheKey, hasPermission ? "1" : "0", expireSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            logError("缓存权限结果失败", e);
        }
    }

    /**
     * 清除权限缓存
     *
     * @param appId 应用ID
     * @param interfaceId 接口ID
     */
    private void clearPermissionCache(Long appId, Long interfaceId) {
        try {
            String cacheKey = PERMISSION_CACHE_PREFIX + appId + ":" + interfaceId;
            redisTemplate.delete(cacheKey);

            // 同时清除应用接口集合缓存
            String appCacheKey = APP_INTERFACES_PREFIX + appId;
            redisTemplate.opsForSet().remove(appCacheKey, interfaceId.toString());
        } catch (Exception e) {
            logError("清除权限缓存失败", e);
        }
    }
}
