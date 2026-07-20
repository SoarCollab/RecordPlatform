package cn.flying.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StorageProperties DirectUploadConfig Unit Tests")
class StoragePropertiesDirectUploadConfigTest {

    private static final long FOUR_GIB = 4L * 1024 * 1024 * 1024;
    private static final long ONE_HUNDRED_MIB = 100L * 1024 * 1024;

    /**
     * 验证降级写不能在关闭同步证据时启动，即使尚未加载拓扑也必须失败关闭。
     */
    @Test
    @DisplayName("Should reject degraded writes when durable sync tracking is disabled")
    void shouldRejectDegradedWritesWithoutDurableTracking() {
        StorageProperties properties = new StorageProperties();
        properties.getDegradedWrite().setEnabled(true);
        properties.getDegradedWrite().setTrackForSync(false);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("track-for-sync must be enabled");
    }

    /**
     * 验证代码默认值与 application/Nacos 模板对外发布的直传配置基线一致。
     */
    @Test
    @DisplayName("Should keep published direct-upload defaults")
    void shouldKeepPublishedDirectUploadDefaults() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();

        assertThat(config.getEffectiveMaxFileSizeBytes()).isEqualTo(FOUR_GIB);
        assertThat(config.getEffectiveMaxPartSizeBytes()).isEqualTo(ONE_HUNDRED_MIB);
        assertThat(config.getEffectiveStreamBufferBytes()).isEqualTo(64 * 1024);
        assertThat(config.getEffectiveTransferTimeoutSeconds()).isEqualTo(300);
        assertThat(config.getEffectiveLockWaitSeconds()).isEqualTo(5);
        assertThat(config.getEffectiveStagingRetentionHours()).isEqualTo(48);
        assertThat(config.isCleanupEnabled()).isTrue();
        assertThat(config.getEffectiveCleanupIntervalMillis()).isEqualTo(3_600_000L);
        assertThat(config.getEffectiveCleanupInitialDelayMillis()).isEqualTo(300_000L);
        assertThat(config.getEffectiveCleanupBatchSize()).isEqualTo(200);
        assertThat(config.getEffectiveCleanupClaimLeaseSeconds()).isEqualTo(600);
    }

    /**
     * 验证公开 YAML 使用的毫秒键可被 Spring relaxed binding 写入真实字段，而不是被默认值掩盖。
     */
    @Test
    @DisplayName("Should bind published cleanup millisecond keys")
    void shouldBindPublishedCleanupMillisecondKeys() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "storage.direct-upload.cleanup-interval-millis", 120_000L,
                "storage.direct-upload.cleanup-initial-delay-millis", 45_000L
        ));

        StorageProperties properties = new Binder(source)
                .bind("storage", Bindable.of(StorageProperties.class))
                .orElseThrow(() -> new IllegalStateException("storage properties were not bound"));

        assertThat(properties.getDirectUpload().getEffectiveCleanupIntervalMillis())
                .isEqualTo(120_000L);
        assertThat(properties.getDirectUpload().getEffectiveCleanupInitialDelayMillis())
                .isEqualTo(45_000L);
    }

    /**
     * 验证极端文件和分片配置不会突破公开直传合同或进入溢出风险区间。
     */
    @Test
    @DisplayName("Should cap direct-upload size limits at the public contract")
    void shouldCapDirectUploadSizeLimitsAtPublicContract() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
        config.setMaxFileSizeBytes(Long.MAX_VALUE);
        config.setMaxPartSizeBytes(Long.MAX_VALUE);

        assertThat(config.getEffectiveMaxFileSizeBytes()).isEqualTo(FOUR_GIB);
        assertThat(config.getEffectiveMaxPartSizeBytes()).isEqualTo(ONE_HUNDRED_MIB);
    }

    /**
     * 验证分片上限仍受更小的有效文件上限约束。
     */
    @Test
    @DisplayName("Should keep part limit within the effective file limit")
    void shouldKeepPartLimitWithinEffectiveFileLimit() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
        config.setMaxFileSizeBytes(32L * 1024 * 1024);
        config.setMaxPartSizeBytes(Long.MAX_VALUE);

        assertThat(config.getEffectiveMaxPartSizeBytes()).isEqualTo(32L * 1024 * 1024);
    }

    /**
     * 验证异常传输超时被限制为 30 分钟，保证单调时钟截止点和占锁时间有界。
     */
    @Test
    @DisplayName("Should cap transfer timeout at thirty minutes")
    void shouldCapTransferTimeoutAtThirtyMinutes() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
        config.setTransferTimeoutSeconds(Integer.MAX_VALUE);

        assertThat(config.getEffectiveTransferTimeoutSeconds()).isEqualTo(1_800);
    }

    /**
     * 验证锁等待配置被限制为一分钟，同时保留零等待的非阻塞语义。
     */
    @Test
    @DisplayName("Should keep lock wait between zero and one minute")
    void shouldKeepLockWaitBetweenZeroAndOneMinute() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
        config.setLockWaitSeconds(Integer.MAX_VALUE);

        assertThat(config.getEffectiveLockWaitSeconds()).isEqualTo(60);

        config.setLockWaitSeconds(Integer.MIN_VALUE);

        assertThat(config.getEffectiveLockWaitSeconds()).isZero();
    }

    /**
     * 验证非正文件、分片和传输超时仍回退到安全默认值。
     */
    @Test
    @DisplayName("Should fall back to safe defaults for non-positive values")
    void shouldFallBackToSafeDefaultsForNonPositiveValues() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();
        config.setMaxFileSizeBytes(0);
        config.setMaxPartSizeBytes(Long.MIN_VALUE);
        config.setTransferTimeoutSeconds(0);

        assertThat(config.getEffectiveMaxFileSizeBytes()).isEqualTo(FOUR_GIB);
        assertThat(config.getEffectiveMaxPartSizeBytes()).isEqualTo(ONE_HUNDRED_MIB);
        assertThat(config.getEffectiveTransferTimeoutSeconds()).isEqualTo(300);
    }

    /**
     * 验证零、负数和 1ms 清理间隔不能形成 Redis 热循环，超大间隔不会越过一天上限。
     */
    @Test
    @DisplayName("Should bound cleanup interval away from a hot loop")
    void shouldBoundCleanupIntervalAwayFromHotLoop() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();

        config.setCleanupIntervalMillis(0);
        assertThat(config.getEffectiveCleanupIntervalMillis()).isEqualTo(3_600_000L);

        config.setCleanupIntervalMillis(-1);
        assertThat(config.getEffectiveCleanupIntervalMillis()).isEqualTo(3_600_000L);

        config.setCleanupIntervalMillis(1);
        assertThat(config.getEffectiveCleanupIntervalMillis()).isEqualTo(60_000L);

        config.setCleanupIntervalMillis(Long.MAX_VALUE);
        assertThat(config.getEffectiveCleanupIntervalMillis()).isEqualTo(86_400_000L);
    }

    /**
     * 验证首次延迟允许显式立即启动，但负数回退默认值且超大值限制为一天。
     */
    @Test
    @DisplayName("Should bound cleanup initial delay")
    void shouldBoundCleanupInitialDelay() {
        StorageProperties.DirectUploadConfig config = new StorageProperties.DirectUploadConfig();

        config.setCleanupInitialDelayMillis(0);
        assertThat(config.getEffectiveCleanupInitialDelayMillis()).isZero();

        config.setCleanupInitialDelayMillis(-1);
        assertThat(config.getEffectiveCleanupInitialDelayMillis()).isEqualTo(300_000L);

        config.setCleanupInitialDelayMillis(1);
        assertThat(config.getEffectiveCleanupInitialDelayMillis()).isEqualTo(1L);

        config.setCleanupInitialDelayMillis(Long.MAX_VALUE);
        assertThat(config.getEffectiveCleanupInitialDelayMillis()).isEqualTo(86_400_000L);
    }
}
