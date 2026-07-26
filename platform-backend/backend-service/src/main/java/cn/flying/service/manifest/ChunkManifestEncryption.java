package cn.flying.service.manifest;

/**
 * 分片 manifest 的版本化加密描述，用于绑定 framed AEAD 的跨端解析合同。
 *
 * @param formatVersion 加密格式版本
 * @param algorithmSuite 算法套件
 * @param fileNonce 文件级随机 nonce，Base64URL 无填充
 * @param framePlainSize 单帧明文大小
 * @param keyDerivation 帧密钥派生算法
 * @param nonceDerivation 帧 nonce 派生算法
 * @param aadSchema AAD 字节合同标识
 * @param tagSize AEAD 标签字节数
 */
public record ChunkManifestEncryption(
        Integer formatVersion,
        String algorithmSuite,
        String fileNonce,
        Integer framePlainSize,
        String keyDerivation,
        String nonceDerivation,
        String aadSchema,
        Integer tagSize
) {

    public static final int FORMAT_NONE = 0;
    public static final int FORMAT_LEGACY_V1 = 1;
    public static final int FORMAT_FRAMED_V2 = 2;
    public static final String SUITE_FRAMED_V2 = "RP-AES256-GCM-FRAMED-V2";
    public static final String DERIVATION_HKDF_SHA256 = "HKDF-SHA256";
    public static final String AAD_SCHEMA_FRAMED_V2 = "cn.flying.framed-aead.aad.v2";
    public static final int TAG_SIZE_BYTES = 16;
    public static final int FILE_NONCE_SIZE_BYTES = 16;
    public static final int MIN_FRAME_PLAIN_SIZE = 64 * 1024;
    public static final int MAX_FRAME_PLAIN_SIZE = 4 * 1024 * 1024;
}
