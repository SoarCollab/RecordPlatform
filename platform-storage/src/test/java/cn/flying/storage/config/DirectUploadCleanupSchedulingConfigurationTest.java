package cn.flying.storage.config;

import cn.flying.storage.service.DirectUploadStagingCleanupService;
import cn.flying.storage.service.DirectUploadLockManager;
import cn.flying.storage.service.DirectUploadOperationIntentStore;
import cn.flying.storage.service.DirectUploadStagingTracker;
import cn.flying.storage.core.S3ClientManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DirectUploadCleanupSchedulingConfiguration Unit Tests")
class DirectUploadCleanupSchedulingConfigurationTest {

    /**
     * 验证默认任务与 staging 清理使用两个不同 scheduler，且清理线程池固定为单线程。
     */
    @Test
    @DisplayName("Should isolate cleanup on a dedicated single-thread scheduler")
    void shouldIsolateCleanupOnDedicatedSingleThreadScheduler() throws Exception {
        DirectUploadCleanupSchedulingConfiguration configuration =
                new DirectUploadCleanupSchedulingConfiguration();
        ThreadPoolTaskSchedulerBuilder defaultBuilder = new ThreadPoolTaskSchedulerBuilder()
                .poolSize(2)
                .threadNamePrefix("storage-default-scheduling-");
        ThreadPoolTaskScheduler defaultScheduler = configuration.defaultTaskScheduler(defaultBuilder);
        ThreadPoolTaskScheduler cleanupScheduler = configuration.directUploadCleanupScheduler();

        assertThat(defaultScheduler).isNotSameAs(cleanupScheduler);
        assertThat(defaultScheduler.getPoolSize()).isEqualTo(2);
        assertThat(cleanupScheduler.getPoolSize()).isEqualTo(1);

        cleanupScheduler.initialize();
        try {
            CountDownLatch executed = new CountDownLatch(1);
            AtomicReference<String> threadName = new AtomicReference<>();
            cleanupScheduler.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                executed.countDown();
            });

            assertThat(executed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("storage-direct-upload-cleanup-");
            assertThat(cleanupScheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
        } finally {
            cleanupScheduler.shutdown();
        }
    }

    /**
     * 验证定时注解只读取有界 effective getter，并显式选择专用 scheduler。
     */
    @Test
    @DisplayName("Should route bounded fixed-delay schedule to cleanup scheduler")
    void shouldRouteBoundedFixedDelayScheduleToCleanupScheduler() throws Exception {
        Method cleanupMethod = DirectUploadStagingCleanupService.class
                .getMethod("cleanupExpiredStagingObjects");
        Scheduled scheduled = cleanupMethod.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler())
                .isEqualTo(DirectUploadCleanupSchedulingConfiguration.CLEANUP_SCHEDULER_BEAN_NAME);
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("#{@storageProperties.getDirectUpload().getEffectiveCleanupIntervalMillis()}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("#{@storageProperties.getDirectUpload().getEffectiveCleanupInitialDelayMillis()}");
    }

    /**
     * 验证真实 Spring 调度后处理器可以解析 effective getter SpEL，并同时保留两个 scheduler。
     */
    @Test
    @DisplayName("Should resolve bounded schedule expressions in a Spring context")
    void shouldResolveBoundedScheduleExpressionsInSpringContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingContextConfiguration.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.containsBean(
                            DirectUploadCleanupSchedulingConfiguration.DEFAULT_SCHEDULER_BEAN_NAME)).isTrue();
                    assertThat(context.containsBean(
                            DirectUploadCleanupSchedulingConfiguration.CLEANUP_SCHEDULER_BEAN_NAME)).isTrue();
                    assertThat(context.getBean(
                            DirectUploadCleanupSchedulingConfiguration.DEFAULT_SCHEDULER_BEAN_NAME)).isNotSameAs(
                            context.getBean(
                                    DirectUploadCleanupSchedulingConfiguration.CLEANUP_SCHEDULER_BEAN_NAME));
                });
    }

    /**
     * 提供仅用于调度表达式与 bean 路由验证的最小 Spring 上下文。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(DirectUploadCleanupSchedulingConfiguration.class)
    static class SchedulingContextConfiguration {

        /**
         * 提供定时表达式按名称引用的真实有界配置 bean。
         */
        @Bean(name = "storageProperties")
        StorageProperties storageProperties() {
            return new StorageProperties();
        }

        /**
         * 创建被 Spring 解析 @Scheduled 注解的清理服务，外部边界均使用测试替身。
         */
        @Bean
        DirectUploadStagingCleanupService directUploadStagingCleanupService(
                StorageProperties storageProperties,
                MeterRegistry meterRegistry
        ) {
            return new DirectUploadStagingCleanupService(
                    mock(S3ClientManager.class),
                    storageProperties,
                    mock(DirectUploadStagingTracker.class),
                    mock(DirectUploadLockManager.class),
                    mock(DirectUploadOperationIntentStore.class),
                    meterRegistry
            );
        }

        /**
         * 提供清理服务构造所需的轻量测试指标注册表。
         */
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
