package cn.flying.platformapi.response;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对象存储 HEAD 元数据视图。
 *
 * @param exists 对象是否存在
 * @param filePath 后端侧记录的逻辑分片路径
 * @param fileHash 逻辑分片路径中匹配的文件哈希
 * @param tenantId 逻辑分片路径中的租户 ID
 * @param metadataTenantId 对象元数据中的租户 ID，历史对象可能为空
 * @param nodeName 命中的存储节点名
 * @param contentLength 对象大小，单位字节
 * @param eTag 对象 ETag
 * @param metadataHash 对象元数据中的文件哈希，历史对象可能为空
 */
public record StorageObjectHeadVO(
        boolean exists,
        String filePath,
        String fileHash,
        Long tenantId,
        Long metadataTenantId,
        String nodeName,
        Long contentLength,
        String eTag,
        String metadataHash
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造对象缺失时的 HEAD 结果。
     *
     * @param filePath 后端侧记录的逻辑分片路径
     * @param fileHash 文件哈希
     * @param tenantId 租户 ID
     * @return 缺失对象的 HEAD 视图
     */
    public static StorageObjectHeadVO missing(String filePath, String fileHash, Long tenantId) {
        return new StorageObjectHeadVO(false, filePath, fileHash, tenantId, null, null, null, null, null);
    }
}
