package cn.flying.service.quota;

/**
 * 配额判定结果。
 *
 * @param exceeded 是否超限
 * @param userStorageExceeded 用户存储是否超限
 * @param userFileCountExceeded 用户文件数是否超限
 * @param tenantStorageExceeded 租户存储是否超限
 * @param tenantFileCountExceeded 租户文件数是否超限
 * @param tenantId 租户ID
 * @param userId 用户ID
 * @param userUsedStorageBytes 用户当前已使用存储
 * @param userMaxStorageBytes 用户存储上限
 * @param userUsedFileCount 用户当前文件数
 * @param userMaxFileCount 用户文件数上限
 * @param tenantUsedStorageBytes 租户当前已使用存储
 * @param tenantMaxStorageBytes 租户存储上限
 * @param tenantUsedFileCount 租户当前文件数
 * @param tenantMaxFileCount 租户文件数上限
 * @param incomingFileSizeBytes 本次上传文件大小
 */
public record QuotaDecision(
        boolean exceeded,
        boolean userStorageExceeded,
        boolean userFileCountExceeded,
        boolean tenantStorageExceeded,
        boolean tenantFileCountExceeded,
        Long tenantId,
        Long userId,
        Long userUsedStorageBytes,
        Long userMaxStorageBytes,
        Long userUsedFileCount,
        Long userMaxFileCount,
        Long tenantUsedStorageBytes,
        Long tenantMaxStorageBytes,
        Long tenantUsedFileCount,
        Long tenantMaxFileCount,
        Long incomingFileSizeBytes
) {

    /**
     * 生成超限原因文本，用于审计与错误响应。
     *
     * @returns 原因描述
     */
    public String reason() {
        if (!exceeded) {
            return "OK";
        }
        if (userStorageExceeded) {
            return "用户存储配额超限";
        }
        if (userFileCountExceeded) {
            return "用户文件数量配额超限";
        }
        if (tenantStorageExceeded) {
            return "租户存储配额超限";
        }
        if (tenantFileCountExceeded) {
            return "租户文件数量配额超限";
        }
        return "配额超限";
    }
}
