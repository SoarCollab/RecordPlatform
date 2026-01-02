package cn.flying.storage.tenant;

import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3 兼容存储模块的租户上下文工具类。
 * 从 Dubbo RPC 上下文中读取租户 ID，用于存储路径隔离。
 */
public final class TenantContextUtil {

    private static final Logger log = LoggerFactory.getLogger(TenantContextUtil.class);
    private static final String TENANT_ATTACHMENT_KEY = "tenant.id";
    private static final Long DEFAULT_TENANT_ID = 0L;
    private static final String PATH_PREFIX = "storage";

    private TenantContextUtil() {}

    /**
     * 从 Dubbo RPC 上下文获取租户 ID。
     *
     * @return 租户 ID，如果不存在则返回 null
     */
    public static Long getTenantId() {
        RpcContext serverContext = RpcContext.getServerAttachment();
        String tenantIdStr = serverContext.getAttachment(TENANT_ATTACHMENT_KEY);
        if (tenantIdStr != null && !tenantIdStr.isEmpty()) {
            try {
                return Long.parseLong(tenantIdStr);
            } catch (NumberFormatException e) {
                log.warn("无效的租户 ID: {}", tenantIdStr);
            }
        }
        return null;
    }

    /**
     * 从 Dubbo RPC 上下文获取租户 ID，如果不存在则返回默认值。
     *
     * @return 租户 ID 或默认值 (0)
     */
    public static Long getTenantIdOrDefault() {
        Long tenantId = getTenantId();
        return tenantId != null ? tenantId : DEFAULT_TENANT_ID;
    }

    /**
     * 构建分片存储路径（故障域模式）。
     * 格式: storage/tenant/{tenantId}/chunk/{objectName}
     * 不再包含逻辑节点名，因为分片分布由一致性哈希决定。
     *
     * @param objectName 对象名称（通常是 fileHash）
     * @return 租户隔离的分片路径
     */
    public static String buildChunkPath(String objectName) {
        Long tenantId = getTenantIdOrDefault();
        return String.format("%s/tenant/%d/chunk/%s", PATH_PREFIX, tenantId, objectName);
    }

    /**
     * 构建对象在物理节点中的存储路径（带租户隔离）。
     * 格式: tenant/{tenantId}/{objectName}
     *
     * @param objectName 原始对象名称
     * @return 带租户前缀的对象路径
     */
    public static String buildTenantObjectPath(String objectName) {
        Long tenantId = getTenantIdOrDefault();
        return String.format("tenant/%d/%s", tenantId, objectName);
    }

    /**
     * 从分片路径中解析信息。
     * 格式: storage/tenant/{tenantId}/chunk/{objectName}
     *
     * @param chunkPath 分片路径
     * @return 解析结果，包含租户 ID 和对象名
     */
    public static ParsedChunkPath parseChunkPath(String chunkPath) {
        if (chunkPath == null || chunkPath.isEmpty()) {
            return null;
        }

        // 格式: storage/tenant/{tenantId}/chunk/{objectName}
        String tenantPrefix = PATH_PREFIX + "/tenant/";
        if (chunkPath.startsWith(tenantPrefix)) {
            String remaining = chunkPath.substring(tenantPrefix.length());
            int firstSlash = remaining.indexOf('/');
            if (firstSlash > 0) {
                try {
                    Long tenantId = Long.parseLong(remaining.substring(0, firstSlash));
                    String afterTenant = remaining.substring(firstSlash + 1);

                    // 期望格式: chunk/{objectName}
                    if (afterTenant.startsWith("chunk/")) {
                        String objectName = afterTenant.substring(6);
                        if (!objectName.isEmpty()) {
                            return new ParsedChunkPath(tenantId, objectName);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("解析分片路径失败，无效的租户 ID: {}", chunkPath);
                }
            }
        }

        return null;
    }

    /**
     * 解析后的分片路径信息。
     */
    public record ParsedChunkPath(Long tenantId, String objectName) {}
}
