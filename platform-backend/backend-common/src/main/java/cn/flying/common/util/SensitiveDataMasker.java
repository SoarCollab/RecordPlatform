package cn.flying.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏工具类
 * <p>
 * 用于在日志记录前对敏感字段进行脱敏处理，
 * 防止密码、令牌等敏感信息泄露到日志中。
 */
public final class SensitiveDataMasker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 脱敏后的替换值
     */
    private static final String MASKED_VALUE = "******";

    /**
     * 敏感字段名（不区分大小写匹配）
     */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "oldpassword",
            "old_password",
            "newpassword",
            "new_password",
            "passwd",
            "pwd",
            "secret",
            "token",
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "apikey",
            "api_key",
            "authorization",
            "auth",
            "credential",
            "credentials",
            "code",
            "verificationcode",
            "verification_code",
            "verifycode",
            "verify_code",
            "otp",
            "mfacode",
            "mfa_code",
            "onetimecode",
            "one_time_code",
            "resetcode",
            "reset_code",
            "key",
            "keys",
            "initialkey",
            "initial_key",
            "decryptkey",
            "decrypt_key",
            "decryptionkey",
            "decryption_key",
            "encryptionkey",
            "encryption_key",
            "filekey",
            "file_key",
            "privatekey",
            "private_key",
            "secretkey",
            "secret_key",
            "ssetoken",
            "sharecode",
            "share_code",
            "sharingcode",
            "sharing_code",
            "filehash",
            "file_hash",
            "transactionhash",
            "transaction_hash",
            "contractabi",
            "contract_abi",
            "input",
            "signature",
            "presignedurl",
            "presigned_url",
            "downloadurl",
            "download_url",
            "clientid",
            "client_id"
    );

    /**
     * 日志路径中用于替换敏感路径变量的值。
     */
    private static final String PATH_MASKED_VALUE = "***";

    /**
     * /files 后面这些路径段是路由字面量，不应被当作文件 ID 或文件哈希脱敏。
     */
    private static final Set<String> FILE_ROUTE_LITERALS = Set.of(
            "download-batches",
            "hash",
            "quota",
            "share",
            "shares",
            "stats",
            "upload-sessions",
            "save"
    );

    /**
     * 用于匹配 JSON 中敏感字段的正则表达式
     * 匹配格式：\"fieldName\":\"value\" 或 \"fieldName\":value
     * 支持值中包含转义引号的情况，如 \"password\":\"test\\\"123\"
     */
    private static final List<Pattern> SENSITIVE_PATTERNS = SENSITIVE_FIELD_NAMES.stream()
            .map(field -> Pattern.compile(
                    "\"" + field + "\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\]]+)",
                    Pattern.CASE_INSENSITIVE
            ))
            .toList();

    private SensitiveDataMasker() {
        // 私有构造函数，防止实例化
    }

    /**
     * 对 JSON 字符串中的敏感字段进行脱敏
     *
     * @param json JSON 字符串
     * @return 脱敏后的 JSON 字符串
     */
    public static String maskSensitiveFields(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }

        String result = json;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            result = pattern.matcher(result).replaceAll(match -> {
                String matched = match.group();
                int colonIndex = matched.indexOf(':');
                if (colonIndex == -1) {
                    return matched;
                }
                String fieldPart = matched.substring(0, colonIndex + 1);
                return fieldPart + "\"" + MASKED_VALUE + "\"";
            });
        }
        return result;
    }

    /**
     * 对日志路径中的分享码、文件 ID、文件哈希、交易哈希和上传会话 ID 做路径段级脱敏。
     *
     * @param path 原始请求路径
     * @return 脱敏后的请求路径
     */
    public static String maskSensitivePathSegments(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }

        PathParts pathParts = splitPath(path);
        String[] segments = pathParts.path().split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i].toLowerCase(Locale.ROOT);
            if (("shares".equals(segment) || "share".equals(segment) || "transactions".equals(segment))
                    && hasMaskableNextSegment(segments, i)) {
                segments[++i] = PATH_MASKED_VALUE;
                continue;
            }

            if ("upload-sessions".equals(segment) && hasMaskableNextSegment(segments, i)) {
                segments[++i] = PATH_MASKED_VALUE;
                continue;
            }

            if ("hash".equals(segment) && hasMaskableNextSegment(segments, i)) {
                segments[++i] = PATH_MASKED_VALUE;
                continue;
            }

            if ("files".equals(segment)
                    && hasMaskableNextSegment(segments, i)
                    && isSensitiveFilePathVariable(segments[i + 1])) {
                segments[++i] = PATH_MASKED_VALUE;
            }
        }
        return String.join("/", segments) + pathParts.suffix();
    }

    /**
     * 判断指定位置后是否存在可脱敏路径段。
     */
    private static boolean hasMaskableNextSegment(String[] segments, int currentIndex) {
        return currentIndex + 1 < segments.length
                && segments[currentIndex + 1] != null
                && !segments[currentIndex + 1].isBlank()
                && !PATH_MASKED_VALUE.equals(segments[currentIndex + 1]);
    }

    /**
     * 判断 /files 后的路径段是否为敏感变量，而非静态路由字面量。
     */
    private static boolean isSensitiveFilePathVariable(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        return !FILE_ROUTE_LITERALS.contains(segment.toLowerCase(Locale.ROOT));
    }

    /**
     * 分离路径主体和查询/锚点后缀，只对路径段本身做脱敏。
     */
    private static PathParts splitPath(String path) {
        int suffixIndex = path.length();
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            suffixIndex = Math.min(suffixIndex, queryIndex);
        }
        int fragmentIndex = path.indexOf('#');
        if (fragmentIndex >= 0) {
            suffixIndex = Math.min(suffixIndex, fragmentIndex);
        }
        return new PathParts(path.substring(0, suffixIndex), path.substring(suffixIndex));
    }

    /**
     * 对 Map 中的敏感字段进行脱敏
     *
     * @param data 原始 Map
     * @return 脱敏后的 Map（新对象，不修改原始数据）
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> maskSensitiveFields(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (isSensitiveField(key)) {
                result.put(key, MASKED_VALUE);
            } else if (value instanceof Map) {
                result.put(key, maskSensitiveFields((Map<String, Object>) value));
            } else if (value instanceof List) {
                result.put(key, maskSensitiveFieldsInList((List<Object>) value));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 对 List 中的敏感字段进行脱敏
     */
    @SuppressWarnings("unchecked")
    private static List<Object> maskSensitiveFieldsInList(List<Object> list) {
        return list.stream()
                .map(item -> {
                    if (item instanceof Map) {
                        return maskSensitiveFields((Map<String, Object>) item);
                    } else if (item instanceof List) {
                        return maskSensitiveFieldsInList((List<Object>) item);
                    }
                    return item;
                })
                .toList();
    }

    /**
     * 对对象进行脱敏处理
     * 先序列化为 JSON，脱敏后返回
     *
     * @param obj 原始对象
     * @return 脱敏后的 JSON 字符串
     */
    public static String maskAndSerialize(Object obj) {
        if (obj == null) {
            return null;
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(obj);
            return maskSensitiveFields(json);
        } catch (JsonProcessingException e) {
            // 序列化失败时返回简单的类名表示
            return "[" + obj.getClass().getSimpleName() + "]";
        }
    }

    /**
     * 对对象列表进行脱敏处理
     *
     * @param objects 对象列表
     * @return 脱敏后的 JSON 字符串
     */
    public static String maskAndSerialize(List<?> objects) {
        if (objects == null || objects.isEmpty()) {
            return "[]";
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(objects);
            return maskSensitiveFields(json);
        } catch (JsonProcessingException e) {
            return "[...]";
        }
    }

    /**
     * 判断字段名是否为敏感字段
     *
     * @param fieldName 字段名
     * @return 是否为敏感字段
     */
    public static boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        String lowerFieldName = fieldName.toLowerCase();
        return SENSITIVE_FIELD_NAMES.contains(lowerFieldName);
    }

    private record PathParts(String path, String suffix) {
    }
}
