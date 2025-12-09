package cn.flying.service.encryption;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/**
 * 分片解密服务
 *
 * <p>提供分片文件解密功能，根据文件头自动识别加密算法。</p>
 *
 * <h3>支持的文件格式：</h3>
 * <ul>
 *   <li><b>v1 格式</b>: [Header:4B][IV:12B][加密数据][Tag] - 带版本头</li>
 * </ul>
 *
 * <p><b>注意</b>：所有加密文件必须包含版本头 (magic bytes 'RP')。</p>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 解密单个分片
 * byte[] plaintext = chunkDecryptionService.decryptChunk(encryptedData, keyBytes);
 *
 * // 获取分片使用的算法（用于调试/日志）
 * String algorithm = chunkDecryptionService.detectAlgorithm(encryptedData);
 * </pre>
 *
 * @author Claude Code
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkDecryptionService {

    private static final int IV_SIZE = 12;  // AES-GCM 和 ChaCha20 都使用 12 字节 IV/Nonce

    private final EncryptionStrategyFactory strategyFactory;

    /**
     * 解密分片数据（自动检测算法）
     *
     * @param encryptedData 加密的分片数据（包含头部、IV、密文、认证标签）
     * @param keyBytes      解密密钥字节数组（32 字节）
     * @return 解密后的明文数据
     * @throws EncryptionException 解密失败时抛出
     */
    public byte[] decryptChunk(byte[] encryptedData, byte[] keyBytes) throws EncryptionException {
        if (encryptedData == null || encryptedData.length == 0) {
            throw new EncryptionException("Encrypted data is empty");
        }
        if (keyBytes == null || keyBytes.length != 32) {
            throw new EncryptionException("Invalid key: expected 32 bytes, got " + (keyBytes == null ? "null" : keyBytes.length));
        }

        // 检测算法并获取对应策略
        byte algorithmId = ChunkFileHeader.parseAlgorithm(encryptedData);
        ChunkEncryptionStrategy strategy = getStrategyById(algorithmId);
        int dataOffset = ChunkFileHeader.getDataOffset(encryptedData);

        log.debug("Decrypting chunk: algorithm={}, hasHeader={}, dataOffset={}",
                ChunkFileHeader.getAlgorithmName(algorithmId), dataOffset > 0, dataOffset);

        // 提取 IV
        if (encryptedData.length < dataOffset + IV_SIZE) {
            throw new EncryptionException("Data too short: cannot extract IV");
        }
        byte[] iv = Arrays.copyOfRange(encryptedData, dataOffset, dataOffset + IV_SIZE);

        // 提取密文（IV 之后的所有数据）
        byte[] ciphertext = Arrays.copyOfRange(encryptedData, dataOffset + IV_SIZE, encryptedData.length);

        // 创建密钥
        SecretKey secretKey = createSecretKey(keyBytes, algorithmId);

        // 解密
        return strategy.decrypt(ciphertext, secretKey, iv);
    }

    /**
     * 流式解密分片数据（用于大文件）
     *
     * @param encryptedData 加密的分片数据
     * @param keyBytes      解密密钥字节数组
     * @return 解密上下文，用于流式处理
     * @throws EncryptionException 初始化失败时抛出
     */
    public DecryptionResult createDecryptionContext(byte[] encryptedData, byte[] keyBytes) throws EncryptionException {
        if (encryptedData == null || encryptedData.length == 0) {
            throw new EncryptionException("Encrypted data is empty");
        }
        if (keyBytes == null || keyBytes.length != 32) {
            throw new EncryptionException("Invalid key: expected 32 bytes");
        }

        byte algorithmId = ChunkFileHeader.parseAlgorithm(encryptedData);
        ChunkEncryptionStrategy strategy = getStrategyById(algorithmId);
        int dataOffset = ChunkFileHeader.getDataOffset(encryptedData);

        // 提取 IV
        byte[] iv = Arrays.copyOfRange(encryptedData, dataOffset, dataOffset + IV_SIZE);
        SecretKey secretKey = createSecretKey(keyBytes, algorithmId);

        EncryptionContext context = strategy.createDecryptionContext(secretKey, iv);

        return new DecryptionResult(
                strategy,
                context,
                dataOffset + IV_SIZE,  // 密文起始位置
                ChunkFileHeader.getAlgorithmName(algorithmId)
        );
    }

    /**
     * 检测分片数据使用的加密算法
     *
     * @param encryptedData 加密的分片数据
     * @return 算法名称（如 "AES-256-GCM" 或 "ChaCha20-Poly1305"）
     * @throws IllegalArgumentException 如果数据没有有效的版本头
     */
    public String detectAlgorithm(byte[] encryptedData) {
        if (encryptedData == null || encryptedData.length == 0) {
            throw new IllegalArgumentException("Encrypted data is empty");
        }
        byte algorithmId = ChunkFileHeader.parseAlgorithm(encryptedData);
        return ChunkFileHeader.getAlgorithmName(algorithmId);
    }

    /**
     * 检查分片数据是否是有效格式（带版本头）
     *
     * @param encryptedData 加密的分片数据
     * @return 如果是有效格式返回 true
     */
    public boolean isValidFormat(byte[] encryptedData) {
        return ChunkFileHeader.hasValidHeader(encryptedData);
    }

    /**
     * 根据算法 ID 获取对应的加密策略
     */
    private ChunkEncryptionStrategy getStrategyById(byte algorithmId) throws EncryptionException {
        return switch (algorithmId) {
            case ChunkFileHeader.ALGORITHM_AES_GCM -> new AesGcmEncryptionStrategy();
            case ChunkFileHeader.ALGORITHM_CHACHA20 -> new ChaCha20EncryptionStrategy();
            default -> throw new EncryptionException("Unknown algorithm ID: " + algorithmId);
        };
    }

    /**
     * 创建与算法匹配的密钥
     */
    private SecretKey createSecretKey(byte[] keyBytes, byte algorithmId) {
        String algorithm = switch (algorithmId) {
            case ChunkFileHeader.ALGORITHM_CHACHA20 -> "ChaCha20";
            default -> "AES";
        };
        return new SecretKeySpec(keyBytes, algorithm);
    }

    /**
     * 解密结果封装（用于流式解密）
     */
    public record DecryptionResult(
            ChunkEncryptionStrategy strategy,
            EncryptionContext context,
            int ciphertextOffset,
            String algorithmName
    ) {}
}
