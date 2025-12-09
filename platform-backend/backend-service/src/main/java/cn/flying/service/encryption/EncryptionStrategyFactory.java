package cn.flying.service.encryption;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.SecureRandom;

/**
 * 加密策略工厂
 *
 * <p>根据配置或自动检测创建最优的加密策略实例。</p>
 *
 * <h3>自动检测逻辑：</h3>
 * <ol>
 *   <li>运行小规模基准测试（可选）</li>
 *   <li>比较 AES-GCM 和 ChaCha20-Poly1305 的加密速度</li>
 *   <li>选择更快的算法</li>
 * </ol>
 *
 * <h3>配置优先级：</h3>
 * <ol>
 *   <li>显式指定 aes-gcm → 使用 AES-GCM</li>
 *   <li>显式指定 chacha20 → 使用 ChaCha20-Poly1305</li>
 *   <li>auto + benchmark → 运行基准测试选择</li>
 *   <li>auto（默认）→ 默认使用 ChaCha20-Poly1305（更安全的默认值）</li>
 * </ol>
 *
 * @author Claude Code
 * @since 2.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptionStrategyFactory {

    private final EncryptionProperties properties;

    private ChunkEncryptionStrategy selectedStrategy;
    private String selectionReason;

    @PostConstruct
    public void initialize() {
        EncryptionAlgorithm algorithm = properties.getAlgorithmEnum();
        log.info("Initializing encryption strategy, configured algorithm: {}", algorithm.getDisplayName());

        switch (algorithm) {
            case AES_GCM -> {
                selectedStrategy = new AesGcmEncryptionStrategy();
                selectionReason = "explicitly configured";
            }
            case CHACHA20 -> {
                selectedStrategy = new ChaCha20EncryptionStrategy();
                selectionReason = "explicitly configured";
            }
            case AUTO -> {
                if (properties.isBenchmarkOnStartup()) {
                    selectedStrategy = selectByBenchmark();
                } else {
                    // 默认选择 ChaCha20（更安全的默认值，无侧信道风险）
                    selectedStrategy = new ChaCha20EncryptionStrategy();
                    selectionReason = "auto-selected (default to ChaCha20 for side-channel resistance)";
                }
            }
        }

        log.info("Selected encryption strategy: {} ({})", selectedStrategy.getAlgorithmName(), selectionReason);
    }

    /**
     * 获取当前选定的加密策略
     *
     * @return 加密策略实例
     */
    public ChunkEncryptionStrategy getStrategy() {
        return selectedStrategy;
    }

    /**
     * 获取策略选择原因（用于调试和日志）
     *
     * @return 选择原因描述
     */
    public String getSelectionReason() {
        return selectionReason;
    }

    /**
     * 获取当前算法名称
     *
     * @return 算法名称
     */
    public String getCurrentAlgorithmName() {
        return selectedStrategy.getAlgorithmName();
    }

    /**
     * 通过基准测试选择最优算法
     */
    private ChunkEncryptionStrategy selectByBenchmark() {
        log.info("Running encryption benchmark to select optimal algorithm...");

        int dataSize = properties.getBenchmarkDataSize();
        int iterations = properties.getBenchmarkIterations();

        byte[] testData = new byte[dataSize];
        new SecureRandom().nextBytes(testData);

        // 预热 JIT
        warmUp();

        // 测试 AES-GCM
        long aesTime = benchmarkAlgorithm(new AesGcmEncryptionStrategy(), testData, iterations);
        log.info("AES-GCM benchmark: {} ms for {} iterations ({} bytes each)",
                aesTime, iterations, dataSize);

        // 测试 ChaCha20-Poly1305
        long chachaTime = benchmarkAlgorithm(new ChaCha20EncryptionStrategy(), testData, iterations);
        log.info("ChaCha20-Poly1305 benchmark: {} ms for {} iterations ({} bytes each)",
                chachaTime, iterations, dataSize);

        // 选择更快的算法
        if (aesTime < chachaTime) {
            double speedup = (double) chachaTime / aesTime;
            selectionReason = String.format("auto-selected by benchmark (AES-GCM %.1fx faster)", speedup);
            return new AesGcmEncryptionStrategy();
        } else {
            double speedup = (double) aesTime / chachaTime;
            selectionReason = String.format("auto-selected by benchmark (ChaCha20 %.1fx faster)", speedup);
            return new ChaCha20EncryptionStrategy();
        }
    }

    /**
     * 预热 JIT 编译器
     */
    private void warmUp() {
        byte[] warmupData = new byte[1024];
        new SecureRandom().nextBytes(warmupData);

        try {
            ChunkEncryptionStrategy aes = new AesGcmEncryptionStrategy();
            ChunkEncryptionStrategy chacha = new ChaCha20EncryptionStrategy();

            for (int i = 0; i < 100; i++) {
                SecretKey aesKey = aes.generateKey();
                byte[] aesIv = aes.generateIv();
                aes.encrypt(warmupData, aesKey, aesIv);

                SecretKey chachaKey = chacha.generateKey();
                byte[] chachaIv = chacha.generateIv();
                chacha.encrypt(warmupData, chachaKey, chachaIv);
            }
        } catch (Exception e) {
            log.warn("Warmup phase failed, benchmark results may be affected", e);
        }
    }

    /**
     * 对单个算法进行基准测试
     * 注意：每次加密都使用新的 IV，符合 AEAD 安全要求
     */
    private long benchmarkAlgorithm(ChunkEncryptionStrategy strategy, byte[] data, int iterations) {
        try {
            SecretKey key = strategy.generateKey();

            long startTime = System.currentTimeMillis();
            for (int i = 0; i < iterations; i++) {
                // 每次迭代生成新的 IV，符合 AEAD 安全要求
                byte[] iv = strategy.generateIv();
                byte[] encrypted = strategy.encrypt(data, key, iv);
                strategy.decrypt(encrypted, key, iv);
            }
            return System.currentTimeMillis() - startTime;
        } catch (Exception e) {
            log.error("Benchmark failed for {}", strategy.getAlgorithmName(), e);
            return Long.MAX_VALUE;
        }
    }

    /**
     * 检查当前环境是否可能有 AES-NI 支持
     * <p>注意：这是一个启发式检测，不保证 100% 准确。</p>
     *
     * @return 如果可能有 AES-NI 支持返回 true
     */
    public static boolean hasLikelyAesNiSupport() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String vendor = System.getProperty("java.vm.vendor", "").toLowerCase();

        // x86/x64 架构通常有 AES-NI（2010 年后的 Intel/AMD CPU）
        boolean isX86 = arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x86");

        // 某些 JVM 实现可能不启用硬件加速
        boolean isHotSpotOrOpenJ9 = vendor.contains("oracle") || vendor.contains("openjdk")
                || vendor.contains("eclipse") || vendor.contains("adoptium");

        return isX86 && isHotSpotOrOpenJ9;
    }

    /**
     * 检测实际的 AES-NI 支持（通过简单的性能测试）
     * <p>如果 AES-GCM 显著快于预期软件实现速度，则认为有硬件加速。</p>
     *
     * @return 如果检测到硬件加速返回 true
     */
    public static boolean detectAesHardwareAcceleration() {
        try {
            byte[] testData = new byte[64 * 1024]; // 64KB
            new SecureRandom().nextBytes(testData);

            AesGcmEncryptionStrategy aes = new AesGcmEncryptionStrategy();
            SecretKey key = aes.generateKey();

            // 预热 - 每次使用新 IV
            for (int i = 0; i < 10; i++) {
                byte[] iv = aes.generateIv();
                aes.encrypt(testData, key, iv);
            }

            // 测量 - 每次使用新 IV，符合 AEAD 安全要求
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                byte[] iv = aes.generateIv();
                aes.encrypt(testData, key, iv);
            }
            long elapsed = System.nanoTime() - start;

            // 计算吞吐量 (MB/s)
            double throughputMBps = (testData.length * 100.0 / 1024 / 1024) / (elapsed / 1e9);

            // 有 AES-NI 时通常 > 1000 MB/s，软件实现约 100-300 MB/s
            boolean hasHardwareAccel = throughputMBps > 500;

            log.debug("AES-GCM throughput: {} MB/s, hardware acceleration: {}",
                    String.format("%.1f", throughputMBps), hasHardwareAccel);
            return hasHardwareAccel;

        } catch (Exception e) {
            log.warn("Failed to detect AES hardware acceleration", e);
            return false;
        }
    }
}
