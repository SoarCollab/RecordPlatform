package cn.flying.service.proof.signed;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Signed proof 专用 canonical JSON 与 SHA-256 实现。
 */
@Component
public class ProofCanonicalizer {

    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String HASH_PREFIX = "sha256:";

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /**
     * 把受信任模型序列化为属性和 map key 稳定排序的 UTF-8 JSON。
     *
     * @param value 受信任证明模型
     * @return canonical UTF-8 bytes
     */
    public byte[] canonicalBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "证明包 canonical JSON 生成失败");
        }
    }

    /**
     * 解析已持久化的 canonical manifest，供历史 proof 使用原签发外部标识重建。
     *
     * @param value 已签发 canonical manifest JSON
     * @return 结构化 manifest
     */
    public SignedProofBundleModel.Manifest parseManifest(String value) {
        try {
            return mapper.readValue(value, SignedProofBundleModel.Manifest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "已签发证明 manifest 不可解析");
        }
    }

    /**
     * 计算带 sha256 前缀的小写十六进制摘要。
     *
     * @param value 输入字节
     * @return 规范 SHA-256
     */
    public String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HASH_PREFIX + HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }

    /**
     * 计算 UTF-8 文本的规范摘要。
     *
     * @param value 文本
     * @return 规范 SHA-256
     */
    public String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
