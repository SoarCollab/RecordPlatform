package cn.flying.fisco_bcos.registry;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 与部署工具共享语义的 ABI 与 EVM bytecode SHA-256 指纹实现。
 */
@Component
public class ContractFingerprintService {

    public static final String ABI_ALGORITHM = "ABI-CANONICAL-JSON-SHA256-V1";
    public static final String BYTECODE_ALGORITHM = "EVM-BYTECODE-SHA256-V1";
    public static final String SOURCE_ALGORITHM = "SOURCE-UTF8-LF-SHA256-V1";

    private static final int MAX_ARTIFACT_BYTES = 5 * 1024 * 1024;
    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");
    private static final Comparator<String> UNICODE_CODE_POINT_ORDER =
            ContractFingerprintService::compareUnicodeCodePoints;

    private final JsonMapper strictJsonMapper;

    /**
     * 创建启用重复 key 检测的 JSON 解析器，避免 ABI 歧义。
     */
    public ContractFingerprintService() {
        // Jackson 3 起 mapper 不可变：特性在 builder 上一次配置完成。
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.strictJsonMapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
    }

    /**
     * 严格读取 artifact catalog。
     *
     * @param bytes catalog UTF-8 bytes
     * @return 结构化 catalog
     */
    public ContractArtifactCatalog readCatalog(byte[] bytes) {
        requireBoundedBytes(bytes, "Contract artifact catalog");
        try {
            return strictJsonMapper.readValue(bytes, ContractArtifactCatalog.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("Invalid contract artifact catalog", e);
        }
    }

    /**
     * 计算 canonical ABI 指纹。
     *
     * @param abiJson ABI JSON 文本
     * @return sha256: 前缀的 lowercase 指纹
     */
    public String fingerprintAbi(String abiJson) {
        return sha256Label(canonicalizeAbi(abiJson).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按 ABI-CANONICAL-JSON-SHA256-V1 生成确定性 JSON。
     *
     * @param abiJson ABI JSON 文本
     * @return canonical compact JSON
     */
    public String canonicalizeAbi(String abiJson) {
        requireBoundedText(abiJson, "ABI");
        try {
            JsonNode root = strictJsonMapper.readTree(abiJson);
            if (!(root instanceof ArrayNode entries)) {
                throw new IllegalArgumentException("ABI root must be a JSON array");
            }

            List<JsonNode> normalizedEntries = new ArrayList<>();
            for (JsonNode entry : entries) {
                JsonNode normalized = normalize(entry);
                if (!normalized.isObject() || !normalized.path("type").isTextual()) {
                    throw new IllegalArgumentException(
                            "Every ABI entry must be an object with a string type");
                }
                normalizedEntries.add(normalized);
            }
            normalizedEntries.sort(Comparator.comparing(
                    this::writeCompactJson,
                    UNICODE_CODE_POINT_ORDER));

            ArrayNode canonical = strictJsonMapper.createArrayNode();
            normalizedEntries.forEach(canonical::add);
            return writeCompactJson(canonical);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid ABI JSON", e);
        }
    }

    /**
     * 计算 EVM-BYTECODE-SHA256-V1 指纹。
     *
     * @param bytecodeHex 带可选 0x 与空白的 bytecode
     * @return sha256: 前缀的 lowercase 指纹
     */
    public String fingerprintBytecode(String bytecodeHex) {
        return sha256Label(decodeBytecode(bytecodeHex));
    }

    /**
     * 递归排序对象 key、保留数组顺序并删除不影响编码的 internalType。
     */
    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = strictJsonMapper.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>(UNICODE_CODE_POINT_ORDER);
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!"internalType".equals(field.getKey())) {
                    sorted.put(field.getKey(), normalize(field.getValue()));
                }
            }
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = strictJsonMapper.createArrayNode();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isValueNode()) {
            return node.deepCopy();
        }
        throw new IllegalArgumentException("Unsupported ABI JSON node: " + node.getNodeType());
    }

    /**
     * 输出无空白的 UTF-8 JSON。
     */
    private String writeCompactJson(JsonNode node) {
        try {
            return strictJsonMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Cannot serialize canonical ABI", e);
        }
    }

    /**
     * 按 Unicode code point 而非 UTF-16 code unit 比较字符串，确保与 Python 指纹工具一致。
     */
    private static int compareUnicodeCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    /**
     * 解码带可选 0x 与任意 ASCII 空白的十六进制 EVM bytecode。
     */
    private byte[] decodeBytecode(String bytecodeHex) {
        requireBoundedText(bytecodeHex, "Bytecode");
        String compact = bytecodeHex.replaceAll("\\s+", "");
        if (compact.startsWith("0x") || compact.startsWith("0X")) {
            compact = compact.substring(2);
        }
        if (compact.isEmpty()) {
            throw new IllegalArgumentException("Bytecode must not be empty");
        }
        if (compact.length() % 2 != 0 || !HEX_PATTERN.matcher(compact).matches()) {
            throw new IllegalArgumentException("Bytecode must be even-length hexadecimal text");
        }
        return HexFormat.of().parseHex(compact);
    }

    /**
     * 限制文本 artifact 的 UTF-8 大小，避免运行时解析巨型输入。
     */
    private void requireBoundedText(String value, String artifactName) {
        if (value == null) {
            throw new IllegalArgumentException(artifactName + " must not be null");
        }
        requireBoundedBytes(value.getBytes(StandardCharsets.UTF_8), artifactName);
    }

    /**
     * 拒绝空或超过统一上限的 artifact bytes。
     */
    private void requireBoundedBytes(byte[] bytes, String artifactName) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException(artifactName + " must not be empty");
        }
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(artifactName + " exceeds size limit");
        }
    }

    /**
     * 输出统一的 SHA-256 文本格式。
     */
    private String sha256Label(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
