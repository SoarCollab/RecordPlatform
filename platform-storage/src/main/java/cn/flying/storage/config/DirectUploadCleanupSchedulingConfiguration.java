package cn.flying.storage.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 隔离直传 staging 清理与节点健康、故障恢复等默认定时任务的执行线程。
 */
@Configuration(proxyBeanMethods = false)
public class DirectUploadCleanupSchedulingConfiguration {

    public static final String DEFAULT_SCHEDULER_BEAN_NAME = "taskScheduler";
    public static final String CLEANUP_SCHEDULER_BEAN_NAME = "directUploadCleanupScheduler";

    /**
     * 在专用清理 scheduler 使 Boot 默认调度器退让时，按 Boot 配置重建默认 scheduler。
     *
     * @param builder Spring Boot 已应用 task scheduling 属性的构建器
     * @return 供其他未指定 qualifier 的定时任务使用的默认 scheduler
     */
    @Bean(name = DEFAULT_SCHEDULER_BEAN_NAME)
    @ConditionalOnMissingBean(name = DEFAULT_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler defaultTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /**
     * 创建固定单线程且不保留取消任务的清理 scheduler；fixed-delay 不会重叠或补跑积压轮次。
     *
     * @return 仅供 direct-upload staging 清理使用的 scheduler
     */
    @Bean(name = CLEANUP_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler directUploadCleanupScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("storage-direct-upload-cleanup-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
