package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stable, non-secret identity fence for refreshing presigned download URLs.
 */
@Schema(description = "下载 URL 刷新身份栅栏")
public record DownloadAccessIdentityVO(
        @Schema(description = "授权来源：OWNER/ADMIN/FRIEND_SHARE/AUTHENTICATED_SHARE/PUBLIC_SHARE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String accessKind,
        @Schema(description = "绑定 tenant/share/actor/file/version/manifest/suite 的 SHA-256 摘要",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String identityHash,
        @Schema(description = "文件版本；历史数据可能为空",
                requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Integer fileVersion,
        @Schema(description = "身份栅栏绑定的 manifest hash",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String manifestHash,
        @Schema(description = "身份栅栏绑定的加密套件或 NONE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String algorithmSuite
) {
}
