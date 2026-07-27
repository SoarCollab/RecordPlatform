package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一次性授权消费后的瞬时密钥材料。
 */
@Schema(description = "仅用于即时浏览器导入的瞬时下载密钥材料")
public record DownloadKeyMaterialVO(
        @Schema(description = "初始密钥，Base64 编码；不得持久化或记录")
        String initialKey,
        @Schema(description = "实际使用的密钥交付协议", example = "grant-v1")
        String protocol
) {
}
