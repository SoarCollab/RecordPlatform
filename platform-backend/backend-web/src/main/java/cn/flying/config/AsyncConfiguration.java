package cn.flying.config;

import cn.flying.common.tenant.TenantContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务和定时任务线程池配置。
 * 实现 MDC 和 TenantContext 在异步线程中的传递，解决日志追踪和多租户上下文丢失问题。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfiguration {

    /**
     * MDC 和租户上下文传递装饰器。
     * 捕获父线程的 MDC 和 TenantContext，在子线程执行前恢复，执行后清理。
     */
    @Bean
    public TaskDecorator contextPropagatingDecorator() {
        return new TaskDecorator() {
            @Override
            @NonNull
            public Runnable decorate(@NonNull Runnable runnable) {
                // 捕获父线程上下文
                Map<String, String> mdcContext = MDC.getCopyOfContextMap();
                Long tenantId = TenantContext.getTenantId();

                return () -> {
                    try {
                        // 恢复上下文到子线程
                        if (mdcContext != null) {
                            MDC.setContextMap(mdcContext);
                        }
                        if (tenantId != null) {
                            TenantContext.setTenantId(tenantId);
                        }
                        runnable.run();
                    } finally {
                        // 清理子线程上下文，防止线程复用时污染
                        MDC.clear();
                        TenantContext.clear();
                    }
                };
            }
        };
    }

    /**
     * 文件处理任务专用线程池。
     * 用于处理文件存证、上链和 MinIO 存储等耗时操作。
     * 已配置 TaskDecorator 以传递 MDC 和 TenantContext。
     */
    @Bean("fileProcessTaskExecutor")
    public Executor fileProcessTaskExecutor(TaskDecorator contextPropagatingDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("file-process-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // 配置上下文传递装饰器
        executor.setTaskDecorator(contextPropagatingDecorator);
        executor.initialize();
        return executor;
    }

    /**
     * 通用异步任务线程池。
     * 用于一般性异步操作，已配置上下文传递。
     */
    @Bean("taskExecutor")
    public Executor taskExecutor(TaskDecorator contextPropagatingDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("async-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(contextPropagatingDecorator);
        executor.initialize();
        return executor;
    }
}