package cn.flying.common.util;

import cn.flying.common.tenant.TenantContext;

/**
 * 租户 Redis Key 工具类。
 * 为 Redis Key 添加租户隔离前缀。
 */
public final class TenantKeyUtils {

    private static final String TENANT_PREFIX_FORMAT = "tenant:%d:";
    private static final long DEFAULT_TENANT_ID = 0L;

    private TenantKeyUtils() {}

    /**
     * 构建带租户隔离的 Redis Key。
     * 格式: tenant:{tenantId}:{originalKey}
     *
     * @param baseKey 原始 Key
     * @return 带租户前缀的 Key
     */
    public static String tenantKey(String baseKey) {
        Long tenantId = TenantContext.getTenantId();
        return tenantKey(baseKey, tenantId);
    }

    /**
     * 构建指定租户的 Redis Key。
     *
     * @param baseKey  原始 Key
     * @param tenantId 租户 ID
     * @return 带租户前缀的 Key
     */
    public static String tenantKey(String baseKey, Long tenantId) {
        long tid = tenantId != null ? tenantId : DEFAULT_TENANT_ID;
        return String.format(TENANT_PREFIX_FORMAT, tid) + baseKey;
    }

    /**
     * 检查 Key 是否应该进行租户隔离。
     * 某些全局 Key（如限流计数器）不应隔离。
     *
     * @param baseKey 原始 Key 前缀
     * @return 是否需要租户隔离
     */
    public static boolean shouldIsolate(String baseKey) {
        // 流量限制相关 Key 基于 IP，不需要租户隔离
        if (baseKey.startsWith(Const.FLOW_LIMIT_COUNTER) ||
            baseKey.startsWith(Const.FLOW_LIMIT_BLOCK)) {
            return false;
        }
        // 其他 Key 默认需要租户隔离
        return true;
    }

    /**
     * 智能构建 Redis Key，根据 Key 类型决定是否添加租户前缀。
     *
     * @param baseKey 原始 Key
     * @return 处理后的 Key
     */
    public static String smartKey(String baseKey) {
        if (shouldIsolate(baseKey)) {
            return tenantKey(baseKey);
        }
        return baseKey;
    }
}
