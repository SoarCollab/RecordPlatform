package cn.flying.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.server.PathContainer;

import java.util.ArrayList;
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
            "encrypteddatakey",
            "encrypted_data_key",
            "wrappeddatakey",
            "wrapped_data_key",
            "wrappingiv",
            "wrapping_iv",
            "ciphertext",
            "vaultciphertext",
            "vault_ciphertext",
            "wrappingcontext",
            "wrapping_context",
            "vaultcontext",
            "vault_context",
            "derivedcontext",
            "derived_context",
            "associateddata",
            "associated_data",
            "context",
            "kmskeyid",
            "kms_key_id",
            "keyid",
            "key_id",
            "keyname",
            "key_name",
            "historicalkeyids",
            "historical_key_ids",
            "masterkey",
            "master_key",
            "vaulttoken",
            "vault_token",
            "filekey",
            "file_key",
            "filedatakey",
            "file_data_key",
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
        RouteSegments rawRouteSegments = collectRouteSegments(segments, false);
        RouteSegments canonicalRouteSegments = collectRouteSegments(segments, true);
        maskSensitiveRouteTargets(segments, rawRouteSegments);
        maskSensitiveRouteTargets(segments, canonicalRouteSegments);
        return String.join("/", segments) + pathParts.suffix();
    }

    /**
     * 按 Spring 路由语义规范化路径，仅供内存中的路由分类使用，返回值禁止直接写入日志。
     *
     * @param path 原始请求路径
     * @return 逐段解码并移除矩阵参数后的路径，不包含查询参数或 fragment
     */
    public static String normalizePathForRouteMatching(String path) {
        if (path == null || path.isBlank()) {
            return path;
        }

        String rawPath = splitPath(path).path();
        String[] segments = rawPath.split("/", -1);
        RouteSegments canonicalRouteSegments = collectRouteSegments(segments, true);
        String normalizedPath = String.join("/", canonicalRouteSegments.values());
        if (rawPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if (hasCanonicalTrailingSeparator(rawPath, segments)
                && !normalizedPath.isEmpty()
                && !normalizedPath.endsWith("/")) {
            normalizedPath += "/";
        }
        return normalizedPath;
    }

    /**
     * 按原始顺序或容器规范化顺序收集参与路由匹配的路径段。
     */
    private static RouteSegments collectRouteSegments(String[] segments, boolean resolveParentSegments) {
        List<Integer> rawIndexes = new ArrayList<>(segments.length);
        List<String> values = new ArrayList<>(segments.length);
        for (int i = 0; i < segments.length; i++) {
            String value = pathSegmentValueToMatch(segments[i]);
            if (value == null || value.isEmpty() || ".".equals(value)) {
                continue;
            }
            if ("..".equals(value) && resolveParentSegments) {
                if (!values.isEmpty()) {
                    int lastIndex = values.size() - 1;
                    values.remove(lastIndex);
                    rawIndexes.remove(lastIndex);
                }
                continue;
            }
            rawIndexes.add(i);
            values.add(value);
        }
        return new RouteSegments(rawIndexes, values);
    }

    /**
     * 按给定的路由视图定位并替换敏感路径变量。
     */
    private static void maskSensitiveRouteTargets(String[] rawSegments, RouteSegments routeSegments) {
        for (int i = 0; i < routeSegments.values().size(); i++) {
            String segment = routeSegments.values().get(i).toLowerCase(Locale.ROOT);
            if (("shares".equals(segment) || "share".equals(segment) || "transactions".equals(segment))
                    && hasMaskableRouteTarget(routeSegments, i)) {
                maskRouteTarget(rawSegments, routeSegments, ++i);
                continue;
            }

            if ("upload-sessions".equals(segment) && hasMaskableRouteTarget(routeSegments, i)) {
                maskRouteTarget(rawSegments, routeSegments, ++i);
                continue;
            }

            if ("hash".equals(segment) && hasMaskableRouteTarget(routeSegments, i)) {
                maskRouteTarget(rawSegments, routeSegments, ++i);
                continue;
            }

            if ("files".equals(segment)
                    && hasMaskableRouteTarget(routeSegments, i)
                    && isSensitiveFilePathVariable(routeSegments.values().get(i + 1))) {
                maskRouteTarget(rawSegments, routeSegments, ++i);
            }
        }
    }

    /**
     * 判断路由字面量后是否存在非导航路径变量。
     */
    private static boolean hasMaskableRouteTarget(RouteSegments routeSegments, int currentIndex) {
        if (currentIndex + 1 >= routeSegments.values().size()) {
            return false;
        }
        String targetValue = routeSegments.values().get(currentIndex + 1);
        return targetValue != null && !targetValue.isBlank() && !"..".equals(targetValue);
    }

    /**
     * 将路由视图中的路径变量映射回原始 URI 段并整体替换。
     */
    private static void maskRouteTarget(String[] rawSegments, RouteSegments routeSegments, int routeIndex) {
        rawSegments[routeSegments.rawIndexes().get(routeIndex)] = PATH_MASKED_VALUE;
    }

    /**
     * 判断容器规范化后的路径是否仍保留尾部分隔符。
     */
    private static boolean hasCanonicalTrailingSeparator(String rawPath, String[] segments) {
        if (rawPath.endsWith("/")) {
            return true;
        }
        for (int i = segments.length - 1; i >= 0; i--) {
            String value = pathSegmentValueToMatch(segments[i]);
            if (value == null || value.isEmpty()) {
                continue;
            }
            return ".".equals(value) || "..".equals(value);
        }
        return false;
    }

    /**
     * 判断 /files 后的路径段是否为敏感变量，而非静态路由字面量。
     */
    private static boolean isSensitiveFilePathVariable(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        return !FILE_ROUTE_LITERALS.contains(pathSegmentValueToMatch(segment).toLowerCase(Locale.ROOT));
    }

    /**
     * 按 Spring 路由匹配语义解码单个路径段并移除矩阵参数，避免编码形式绕过脱敏。
     */
    private static String pathSegmentValueToMatch(String segment) {
        if (segment == null || segment.isEmpty()) {
            return segment;
        }
        if (segment.indexOf('%') < 0 && segment.indexOf(';') < 0) {
            return segment;
        }
        try {
            return parsePathSegmentValueToMatch(segment);
        } catch (IllegalArgumentException exception) {
            int matrixParameterIndex = segment.indexOf(';');
            if (matrixParameterIndex < 0) {
                return segment;
            }

            String segmentWithoutMatrixParameters = segment.substring(0, matrixParameterIndex);
            try {
                return parsePathSegmentValueToMatch(segmentWithoutMatrixParameters);
            } catch (IllegalArgumentException ignored) {
                return segmentWithoutMatrixParameters;
            }
        }
    }

    /**
     * 解析单个路径段，返回 Spring 路由匹配实际使用的解码值。
     */
    private static String parsePathSegmentValueToMatch(String segment) {
        return PathContainer.parsePath(segment).elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .map(PathContainer.PathSegment::valueToMatch)
                .findFirst()
                .orElse(segment);
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

    /**
     * 保存规范路由段及其在原始 URI 中的索引映射。
     */
    private record RouteSegments(List<Integer> rawIndexes, List<String> values) {
    }
}
