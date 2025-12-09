package cn.flying.service.encryption;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 加密分片文件头解析工具
 *
 * <p>定义加密分片文件的版本头格式，用于标识使用的加密算法。</p>
 *
 * <h3>文件头格式 (4 bytes)：</h3>
 * <pre>
 * Byte 0-1: 魔数 (0x52 0x50 = "RP" for RecordPlatform)
 * Byte 2:   版本号 (0x01 = v1)
 * Byte 3:   算法标识 (0x01 = AES-GCM, 0x02 = ChaCha20-Poly1305)
 * </pre>
 *
 * <h3>完整分片结构：</h3>
 * <pre>
 * [Header: 4B] [IV/Nonce: 12B] [加密数据] [认证标签] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
 * </pre>
 *
 * <h3>后向兼容：</h3>
 * <p>旧版本文件（无头部）以 IV 字节开头，不会以 0x52 0x50 开头，因此可以区分。</p>
 *
 * @author Claude Code
 * @since 2.0.0
 */
public final class ChunkFileHeader {

    /** 魔数：'R' 'P' (RecordPlatform) */
    public static final byte[] MAGIC = {0x52, 0x50};

    /** 当前版本号 */
    public static final byte VERSION = 0x01;

    /** 算法标识：AES-256-GCM */
    public static final byte ALGORITHM_AES_GCM = 0x01;

    /** 算法标识：ChaCha20-Poly1305 */
    public static final byte ALGORITHM_CHACHA20 = 0x02;

    /** 文件头大小 */
    public static final int HEADER_SIZE = 4;

    /** 旧版本（无头部）的默认算法 - AES-GCM */
    public static final byte LEGACY_ALGORITHM = ALGORITHM_AES_GCM;

    private ChunkFileHeader() {
        // 工具类禁止实例化
    }

    /**
     * 创建文件头字节数组
     *
     * @param algorithm 算法标识 ({@link #ALGORITHM_AES_GCM} 或 {@link #ALGORITHM_CHACHA20})
     * @return 4 字节的文件头
     */
    public static byte[] createHeader(byte algorithm) {
        byte[] header = new byte[HEADER_SIZE];
        header[0] = MAGIC[0];
        header[1] = MAGIC[1];
        header[2] = VERSION;
        header[3] = algorithm;
        return header;
    }

    /**
     * 为指定策略创建文件头
     *
     * @param strategy 加密策略
     * @return 4 字节的文件头
     */
    public static byte[] createHeader(ChunkEncryptionStrategy strategy) {
        byte algorithmId = getAlgorithmId(strategy);
        return createHeader(algorithmId);
    }

    /**
     * 获取策略对应的算法标识
     *
     * @param strategy 加密策略
     * @return 算法标识字节
     */
    public static byte getAlgorithmId(ChunkEncryptionStrategy strategy) {
        if (strategy instanceof AesGcmEncryptionStrategy) {
            return ALGORITHM_AES_GCM;
        } else if (strategy instanceof ChaCha20EncryptionStrategy) {
            return ALGORITHM_CHACHA20;
        } else {
            throw new IllegalArgumentException("Unknown encryption strategy: " + strategy.getClass().getName());
        }
    }

    /**
     * 检查字节数组是否以有效的文件头开头
     *
     * @param data 数据字节数组（至少 4 字节）
     * @return 如果是有效的版本化文件头返回 true
     */
    public static boolean hasValidHeader(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            return false;
        }
        return data[0] == MAGIC[0] && data[1] == MAGIC[1];
    }

    /**
     * 检查字节数组是否以有效的文件头开头（从偏移位置检查）
     *
     * @param data   数据字节数组
     * @param offset 起始偏移
     * @return 如果是有效的版本化文件头返回 true
     */
    public static boolean hasValidHeader(byte[] data, int offset) {
        if (data == null || data.length < offset + HEADER_SIZE) {
            return false;
        }
        return data[offset] == MAGIC[0] && data[offset + 1] == MAGIC[1];
    }

    /**
     * 解析文件头获取算法标识
     *
     * @param data 数据字节数组（至少 4 字节）
     * @return 算法标识字节，如果无有效头部返回 {@link #LEGACY_ALGORITHM}
     */
    public static byte parseAlgorithm(byte[] data) {
        if (!hasValidHeader(data)) {
            return LEGACY_ALGORITHM;
        }
        return data[3];
    }

    /**
     * 解析文件头获取算法标识（从偏移位置）
     *
     * @param data   数据字节数组
     * @param offset 起始偏移
     * @return 算法标识字节，如果无有效头部返回 {@link #LEGACY_ALGORITHM}
     */
    public static byte parseAlgorithm(byte[] data, int offset) {
        if (!hasValidHeader(data, offset)) {
            return LEGACY_ALGORITHM;
        }
        return data[offset + 3];
    }

    /**
     * 解析文件头获取版本号
     *
     * @param data 数据字节数组（至少 4 字节）
     * @return 版本号，如果无有效头部返回 0
     */
    public static byte parseVersion(byte[] data) {
        if (!hasValidHeader(data)) {
            return 0;
        }
        return data[2];
    }

    /**
     * 获取加密数据的起始偏移（跳过头部）
     *
     * @param data 数据字节数组
     * @return 加密数据起始偏移（有头部返回 4，无头部返回 0）
     */
    public static int getDataOffset(byte[] data) {
        return hasValidHeader(data) ? HEADER_SIZE : 0;
    }

    /**
     * 获取算法名称（用于日志）
     *
     * @param algorithmId 算法标识字节
     * @return 算法名称字符串
     */
    public static String getAlgorithmName(byte algorithmId) {
        return switch (algorithmId) {
            case ALGORITHM_AES_GCM -> "AES-256-GCM";
            case ALGORITHM_CHACHA20 -> "ChaCha20-Poly1305";
            default -> "Unknown(" + algorithmId + ")";
        };
    }

    /**
     * 将算法名称转换为标识字节
     *
     * @param algorithmName 算法名称（如 "AES-256-GCM" 或 "ChaCha20-Poly1305"）
     * @return 算法标识字节
     */
    public static byte fromAlgorithmName(String algorithmName) {
        if (algorithmName == null) {
            return LEGACY_ALGORITHM;
        }
        String normalized = algorithmName.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (normalized.contains("aes") || normalized.contains("gcm")) {
            return ALGORITHM_AES_GCM;
        }
        if (normalized.contains("chacha") || normalized.contains("poly1305")) {
            return ALGORITHM_CHACHA20;
        }
        return LEGACY_ALGORITHM;
    }
}
