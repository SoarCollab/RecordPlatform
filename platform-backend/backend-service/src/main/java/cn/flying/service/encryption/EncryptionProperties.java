package cn.flying.service.encryption;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 加密配置属性
 *
 * <p>通过 application.yml 配置加密算法选择。</p>
 *
 * <h3>配置示例：</h3>
 * <pre>
 * file:
 *   encryption:
 *     algorithm: chacha20  # 可选: aes-gcm, chacha20, auto
 *     benchmark-on-startup: false  # 是否启动时运行基准测试（用于 auto 模式）
 * </pre>
 *
 * <h3>算法选择指南：</h3>
 * <ul>
 *   <li><b>aes-gcm</b>: 推荐用于有 AES-NI 的服务器（Intel/AMD 现代 CPU）</li>
 *   <li><b>chacha20</b>: 推荐用于容器、云环境、或需要抵抗侧信道攻击的场景</li>
 *   <li><b>auto</b>: 自动检测硬件加速并选择最优算法</li>
 * </ul>
 *
 * @author Claude Code
 * @since 2.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "file.encryption")
public class EncryptionProperties {

    /**
     * 加密算法选择
     * <ul>
     *   <li>aes-gcm: AES-256-GCM (需要 AES-NI 硬件加速)</li>
     *   <li>chacha20: ChaCha20-Poly1305 (纯软件，跨平台一致)</li>
     *   <li>auto: 自动检测最优算法（默认）</li>
     * </ul>
     */
    private String algorithm = "chacha20";

    /**
     * 是否在启动时运行基准测试（仅 auto 模式有效）
     * <p>设置为 true 时，会运行小规模加密测试来判断哪种算法更快。</p>
     */
    private boolean benchmarkOnStartup = false;

    /**
     * 基准测试数据大小（字节）
     * <p>默认 1MB，用于 auto 模式的性能检测。</p>
     */
    private int benchmarkDataSize = 1024 * 1024;

    /**
     * 基准测试迭代次数
     */
    private int benchmarkIterations = 3;

    /**
     * 获取解析后的算法枚举
     */
    public EncryptionAlgorithm getAlgorithmEnum() {
        return EncryptionAlgorithm.fromConfigValue(algorithm);
    }
}
