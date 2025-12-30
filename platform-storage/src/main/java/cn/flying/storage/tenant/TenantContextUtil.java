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
     * 构建包含租户隔离的存储路径。
     * 格式: storage/tenant/{tenantId}/node/{logicNode}/{objectName}
     *
     * @param logicNodeName 逻辑节点名称
     * @param objectName    对象名称（通常是 fileHash）
     * @return 租户隔离的存储路径
     */
    public static String buildTenantPath(String logicNodeName, String objectName) {
        Long tenantId = getTenantIdOrDefault();
        return String.format("%s/tenant/%d/node/%s/%s", PATH_PREFIX, tenantId, logicNodeName, objectName);
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
     * 从租户隔离的逻辑路径中解析原始对象名。
     * 格式: storage/tenant/{tenantId}/node/{logicNode}/{objectName}
     *
     * @param tenantPath 租户隔离的路径
     * @return 解析结果，包含租户 ID、逻辑节点名和对象名
     */
    public static ParsedTenantPath parseTenantPath(String tenantPath) {
        if (tenantPath == null || tenantPath.isEmpty()) {
            return null;
        }

        // 格式: storage/tenant/{tenantId}/node/{logicNode}/{objectName}
        String tenantPrefix = PATH_PREFIX + "/tenant/";
        if (tenantPath.startsWith(tenantPrefix)) {
            String remaining = tenantPath.substring(tenantPrefix.length());
            int firstSlash = remaining.indexOf('/');
            if (firstSlash > 0) {
                try {
                    Long tenantId = Long.parseLong(remaining.substring(0, firstSlash));
                    String afterTenant = remaining.substring(firstSlash + 1);

                    // 期望格式: node/{logicNode}/{objectName}
                    if (afterTenant.startsWith("node/")) {
                        String nodeAndObject = afterTenant.substring(5);
                        int lastSlash = nodeAndObject.lastIndexOf('/');
                        if (lastSlash > 0) {
                            String logicNode = nodeAndObject.substring(0, lastSlash);
                            String objectName = nodeAndObject.substring(lastSlash + 1);
                            return new ParsedTenantPath(tenantId, logicNode, objectName);
                        }
                    }
                } catch (NumberFormatException e) {
                    log.warn("解析租户路径失败，无效的租户 ID: {}", tenantPath);
                }
            }
        }

        return null;
    }

    /**
     * 解析后的租户路径信息。
     */
    public record ParsedTenantPath(Long tenantId, String logicNodeName, String objectName) {}
}
