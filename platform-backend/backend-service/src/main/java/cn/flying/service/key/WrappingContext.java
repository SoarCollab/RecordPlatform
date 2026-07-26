package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/**
 * 文件密钥信封的强类型认证上下文。
 */
public record WrappingContext(
        Long tenantId,
        Long fileId,
        String fileHash,
        String recipientType,
        Long recipientId,
        Integer logicalKeyVersion,
        String algorithmSuite,
        String schema
) {

    public static final String LOCAL_AAD_V1 = "rp-file-envelope-aad-v1";
    public static final String EXTERNAL_CONTEXT_V2 = "rp-file-envelope-context-v2";
    private static final Set<String> SUPPORTED_SCHEMAS = Set.of(LOCAL_AAD_V1, EXTERNAL_CONTEXT_V2);

    /**
     * 校验并返回指定 schema 的规范认证字节。
     */
    public byte[] canonicalBytes() {
        validate();
        String canonical = switch (schema) {
            case LOCAL_AAD_V1 -> tenantId + "|" + fileId + "|" + fileHash + "|" + recipientType
                    + "|" + recipientId + "|" + logicalKeyVersion + "|" + algorithmSuite;
            case EXTERNAL_CONTEXT_V2 -> String.join("\n",
                    EXTERNAL_CONTEXT_V2,
                    tenantId.toString(),
                    fileId.toString(),
                    fileHash,
                    recipientType,
                    recipientId.toString(),
                    algorithmSuite);
            default -> throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封 context schema 不受支持");
        };
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 计算规范认证上下文的 SHA-256 十六进制摘要。
     */
    public String sha256Hex() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (GeneralException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GeneralException(ResultEnum.ENCRYPTION_ERROR, "文件密钥信封上下文哈希失败");
        }
    }

    /**
     * 使用常量时间比较持久化上下文摘要。
     */
    public boolean matchesHash(String persistedHash) {
        if (persistedHash == null || !persistedHash.matches("[0-9a-fA-F]{64}")) {
            return false;
        }
        byte[] expected = sha256Hex().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = persistedHash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 校验上下文完整性和 schema allowlist。
     */
    private void validate() {
        if (tenantId == null || fileId == null || recipientId == null || logicalKeyVersion == null
                || logicalKeyVersion <= 0 || !StringUtils.hasText(fileHash)
                || !StringUtils.hasText(recipientType) || !StringUtils.hasText(algorithmSuite)
                || !SUPPORTED_SCHEMAS.contains(schema)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件密钥信封认证上下文不完整");
        }
    }

    @Override
    public String toString() {
        return "WrappingContext[schema=" + schema + ", values=REDACTED]";
    }
}
