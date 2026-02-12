package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 配额对账时的用户使用量聚合结果。
 *
 * @param userId 用户ID
 * @param usedStorageBytes 已使用存储（字节）
 * @param usedFileCount 已使用文件数
 */
@Schema(description = "配额对账用户使用量")
public record QuotaUserUsageVO(
        @Schema(description = "用户ID")
        Long userId,
        @Schema(description = "已使用存储（字节）")
        Long usedStorageBytes,
        @Schema(description = "已使用文件数")
        Long usedFileCount
) {

    public Long getUserId() {
        return userId;
    }

    public Long getUsedStorageBytes() {
        return usedStorageBytes;
    }

    public Long getUsedFileCount() {
        return usedFileCount;
    }
}
