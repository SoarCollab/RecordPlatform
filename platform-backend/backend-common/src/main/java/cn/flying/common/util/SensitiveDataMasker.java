package cn.flying.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.server.PathContainer;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    private static final int MAX_LOG_VALUE_CHARS = 65536;
    private static final int MAX_LOG_DEPTH = 32;
    private static final int MAX_LOG_NODES = 4096;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_LOG_VALUE_CHARS)
                    .maxStringLength(MAX_LOG_VALUE_CHARS)
                    .maxNestingDepth(MAX_LOG_DEPTH)
                    .maxNumberLength(128)
                    .build())
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(MAX_LOG_DEPTH).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> CAPABILITY_FIELDS = Set.of(
            "uploadurl", "presignedurl", "downloadurl", "signature", "awsaccesskeyid");

    /**
     * 脱敏后的替换值
     */
    private static final String MASKED_VALUE = "******";

    /**
     * 敏感字段名（不区分大小写匹配）
     */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password",
            "passwordhash",
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
            "uploadurl",
            "upload_url",
            "downloadurl",
            "download_url",
            "grantreference",
            "grant_reference",
            "keygrant",
            "key_grant",
            "downloadsessionid",
            "download_session_id",
            "sessionid",
            "session_id",
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

    /** Bounded field detection for incomplete JSON and textual capability assignments. */
    private static final Pattern LOG_FIELD = Pattern.compile(
            "(?:[\"']([^\"'\\\\]{1,128})[\"']|([a-zA-Z][a-zA-Z0-9_.-]{0,127}))\\s*[:=]");
    private static final Set<String> CANONICAL_SENSITIVE_FIELDS = SENSITIVE_FIELD_NAMES.stream()
            .map(field -> field.replace("_", ""))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

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

        return maskLogString(json, 0, new int[]{MAX_LOG_NODES, MAX_LOG_VALUE_CHARS * 2});
    }

    /** Redact structured copies before previews; malformed or over-budget data never falls back to raw secrets. */
    private static String maskLogString(String text, int depth, int[] budget) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.length() > MAX_LOG_VALUE_CHARS || depth >= MAX_LOG_DEPTH || --budget[0] < 0) {
            return MASKED_VALUE;
        }
        budget[1] -= text.length();
        if (budget[1] < 0) {
            return MASKED_VALUE;
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                Object value = OBJECT_MAPPER.readValue(text, Object.class);
                return OBJECT_MAPPER.writeValueAsString(maskLogValue(value, depth + 1, budget));
            } catch (JsonProcessingException ignored) {
                // Inspect truncated/escaped forms below, without ever logging parser diagnostics.
            }
        }
        String detection = text;
        for (int i = 0; i < 4; i++) {
            String decoded = decodeLogEscapes(detection);
            if (decoded.equals(detection)) {
                break;
            }
            detection = decoded;
        }
        if (!decodeLogEscapes(detection).equals(detection)) {
            return MASKED_VALUE;
        }
        String lower = detection.toLowerCase(Locale.ROOT);
        if (lower.contains("x-amz-") || lower.contains("awsaccesskeyid") || lower.contains("x-goog-")) {
            return MASKED_VALUE;
        }
        var fields = LOG_FIELD.matcher(detection);
        while (fields.find()) {
            String field = fields.group(1) != null ? fields.group(1) : fields.group(2);
            if (isCapabilityField(canonicalField(field))
                    || (detection.indexOf('"') >= 0 && isSensitiveField(field))) {
                return MASKED_VALUE;
            }
        }
        return text;
    }

    /** Decode a bounded detection-only view; never emit decoded strings or depend on permissive URL parsing. */
    private static String decodeLogEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    decoded.append((char) (high * 16 + low));
                    i += 2;
                    continue;
                }
            }
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                if (next == 'u' && i + 5 < value.length()) {
                    try {
                        decoded.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // Keep malformed escapes for the conservative field/value detector.
                    }
                }
                if (next == '\\' || next == '/' || next == '"') {
                    decoded.append(next);
                    i++;
                    continue;
                }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    /** Copy containers with shared depth/node budgets, applying one policy to strings, maps and lists. */
    private static Object maskLogValue(Object value, int depth, int[] budget) {
        if (depth >= MAX_LOG_DEPTH || --budget[0] < 0 || budget[1] <= 0) {
            return MASKED_VALUE;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    copy.put("<omitted>", MASKED_VALUE);
                    break;
                }
                String key = String.valueOf(entry.getKey());
                copy.put(maskLogString(key, depth + 1, budget), isSensitiveField(key)
                        ? MASKED_VALUE : maskLogValue(entry.getValue(), depth + 1, budget));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                if (budget[0] <= 0 || budget[1] <= 0) {
                    copy.add(MASKED_VALUE);
                    break;
                }
                copy.add(maskLogValue(item, depth + 1, budget));
            }
            return copy;
        }
        if (value instanceof CharSequence text) {
            return maskLogString(text.toString(), depth + 1, budget);
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length && budget[0] > 0 && budget[1] > 0; i++) {
                copy.add(maskLogValue(java.lang.reflect.Array.get(value, i), depth + 1, budget));
            }
            return copy;
        }
        if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            try {
                return maskLogValue(OBJECT_MAPPER.readValue(serializeForLog(value), Object.class), depth + 1, budget);
            } catch (IOException ignored) {
                return "[" + value.getClass().getSimpleName() + "]";
            }
        }
        return value;
    }

    /** Create a detached, bounded diagnostic graph; no original Throwable remains in logging arguments. */
    public static Throwable maskThrowable(Throwable throwable) {
        return copyThrowable(throwable, new IdentityHashMap<>(), new int[]{64}, 0);
    }

    /** Preserve exception types, stack locations, causes and suppressed diagnostics without secret messages. */
    private static Throwable copyThrowable(Throwable source, IdentityHashMap<Throwable, Boolean> seen,
                                           int[] budget, int depth) {
        if (source == null) {
            return null;
        }
        if (depth >= MAX_LOG_DEPTH || --budget[0] < 0 || seen.put(source, Boolean.TRUE) != null) {
            return new RuntimeException("<omitted exception graph>");
        }
        RuntimeException copy = new RuntimeException(source.getClass().getName() + ": "
                + maskSensitiveFields(source.getMessage()));
        StackTraceElement[] stack = source.getStackTrace();
        StackTraceElement[] safeStack = new StackTraceElement[Math.min(stack.length, 256)];
        for (int i = 0; i < safeStack.length; i++) {
            StackTraceElement frame = stack[i];
            safeStack[i] = new StackTraceElement(maskSensitiveFields(frame.getClassName()),
                    maskSensitiveFields(frame.getMethodName()), maskSensitiveFields(frame.getFileName()), frame.getLineNumber());
        }
        copy.setStackTrace(safeStack);
        Throwable cause = copyThrowable(source.getCause(), seen, budget, depth + 1);
        if (cause != null) {
            copy.initCause(cause);
        }
        for (Throwable suppressed : source.getSuppressed()) {
            if (budget[0] <= 0) {
                break;
            }
            copy.addSuppressed(copyThrowable(suppressed, seen, budget, depth + 1));
        }
        return copy;
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

        return (Map<String, Object>) maskLogValue(data, 0, new int[]{MAX_LOG_NODES, MAX_LOG_VALUE_CHARS * 2});
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
            Object safeInput = obj instanceof Map || obj instanceof List || obj instanceof CharSequence
                    ? maskLogValue(obj, 0, new int[]{MAX_LOG_NODES, MAX_LOG_VALUE_CHARS * 2}) : obj;
            String json = serializeForLog(safeInput);
            return maskSensitiveFields(json);
        } catch (IOException e) {
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
            String json = serializeForLog(maskLogValue(objects, 0, new int[]{MAX_LOG_NODES, MAX_LOG_VALUE_CHARS * 2}));
            return maskSensitiveFields(json);
        } catch (IOException e) {
            return "[...]";
        }
    }

    /** Bound serialization before allocating a complete DTO/collection log copy. */
    private static String serializeForLog(Object value) throws IOException {
        BoundedLogWriter writer = new BoundedLogWriter();
        OBJECT_MAPPER.writeValue(writer, value);
        return writer.buffer.toString();
    }

    /** Reject oversized serialization instead of returning a potentially secret-bearing prefix. */
    private static final class BoundedLogWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();

        /** Append only within the inspection budget; Jackson stops immediately on overflow. */
        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if (length > MAX_LOG_VALUE_CHARS - buffer.length()) {
                throw new IOException("Log serialization limit exceeded");
            }
            buffer.append(chars, offset, length);
        }

        /** This memory-only writer has no external resource to flush. */
        @Override
        public void flush() { }

        /** Closing the writer never publishes the accumulated sensitive buffer. */
        @Override
        public void close() { }
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
        if (fieldName.length() > MAX_LOG_VALUE_CHARS) {
            return true;
        }
        String canonical = canonicalField(fieldName);
        return CANONICAL_SENSITIVE_FIELDS.contains(canonical) || isCapabilityField(canonical);
    }

    /** Normalize aliases for lookup only, including repeated percent/JSON escapes under a fixed budget. */
    private static String canonicalField(String field) {
        String decoded = field;
        for (int i = 0; i < 4; i++) {
            String next = decodeLogEscapes(decoded);
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        if (!decodeLogEscapes(decoded).equals(decoded)) {
            return "signature";
        }
        return decoded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Match URL aliases and signed-query fields even when their values are detached from the original URL. */
    private static boolean isCapabilityField(String canonical) {
        return CAPABILITY_FIELDS.contains(canonical) || canonical.startsWith("xamz") || canonical.startsWith("xgoog");
    }

    private record PathParts(String path, String suffix) {
    }

    /**
     * 保存规范路由段及其在原始 URI 中的索引映射。
     */
    private record RouteSegments(List<Integer> rawIndexes, List<String> values) {
    }
}
