package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前配额状态响应。
 */
@Schema(description = "当前配额状态")
public record QuotaStatusVO(
        @Schema(description = "租户ID")
        Long tenantId,
        @Schema(description = "用户ID")
        Long userId,
        @Schema(description = "配额执行模式：SHADOW/ENFORCE")
        String enforcementMode,
        @Schema(description = "用户已使用存储（字节）")
        Long userUsedStorageBytes,
        @Schema(description = "用户存储上限（字节）")
        Long userMaxStorageBytes,
        @Schema(description = "用户已使用文件数")
        Long userUsedFileCount,
        @Schema(description = "用户文件数上限")
        Long userMaxFileCount,
        @Schema(description = "租户已使用存储（字节）")
        Long tenantUsedStorageBytes,
        @Schema(description = "租户存储上限（字节）")
        Long tenantMaxStorageBytes,
        @Schema(description = "租户已使用文件数")
        Long tenantUsedFileCount,
        @Schema(description = "租户文件数上限")
        Long tenantMaxFileCount
) {

    public Long getTenantId() {
        return tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEnforcementMode() {
        return enforcementMode;
    }

    public Long getUserUsedStorageBytes() {
        return userUsedStorageBytes;
    }

    public Long getUserMaxStorageBytes() {
        return userMaxStorageBytes;
    }

    public Long getUserUsedFileCount() {
        return userUsedFileCount;
    }

    public Long getUserMaxFileCount() {
        return userMaxFileCount;
    }

    public Long getTenantUsedStorageBytes() {
        return tenantUsedStorageBytes;
    }

    public Long getTenantMaxStorageBytes() {
        return tenantMaxStorageBytes;
    }

    public Long getTenantUsedFileCount() {
        return tenantUsedFileCount;
    }

    public Long getTenantMaxFileCount() {
        return tenantMaxFileCount;
    }
}
