package cn.flying.config;

import cn.flying.common.tenant.TenantContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务和定时任务线程池配置。
 * 实现 MDC 和 TenantContext 在异步线程中的传递，解决日志追踪和多租户上下文丢失问题。
 *
 * <h3>Java 21 Virtual Threads 支持</h3>
 * <p>
 * 本配置提供两种线程模型：
 * <ul>
 *   <li><b>Virtual Thread Executor</b>: 适用于 I/O 密集型操作（数据库查询、远程调用、文件读取）</li>
 *   <li><b>Platform Thread Pool</b>: 适用于 CPU 密集型操作（加密、压缩、计算）</li>
 * </ul>
 * </p>
 *
 * <h3>CQRS 模式支持</h3>
 * <p>
 * Query 操作使用 virtualThreadExecutor（高并发 I/O）<br/>
 * Command 操作使用 fileProcessTaskExecutor（需要事务和顺序保证）
 * </p>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfiguration {

    /**
     * MDC 和租户上下文传递装饰器。
     * 捕获父线程的 MDC 和 TenantContext（包括 tenantId 和 ignoreIsolation），
     * 在子线程执行前恢复，执行后清理。
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
                boolean ignoreIsolation = TenantContext.isIgnoreIsolation();

                return () -> {
                    try {
                        // 恢复上下文到子线程
                        if (mdcContext != null) {
                            MDC.setContextMap(mdcContext);
                        }
                        if (tenantId != null) {
                            TenantContext.setTenantId(tenantId);
                        }
                        TenantContext.setIgnoreIsolation(ignoreIsolation);
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
     * Java 21 Virtual Thread Executor - 适用于 I/O 密集型操作。
     * <p>
     * 虚拟线程特性：
     * <ul>
     *   <li>每个任务创建新的虚拟线程，无需池化</li>
     *   <li>阻塞操作（网络/数据库/文件 I/O）自动让出底层平台线程</li>
     *   <li>支持百万级并发，内存占用极低（~1KB vs 平台线程 ~1MB）</li>
     *   <li>与 MDC/TenantContext 传递装饰器兼容</li>
     * </ul>
     * </p>
     * <p>
     * 适用场景：CQRS Query 操作、远程服务调用、数据库查询
     * </p>
     */
    @Bean("virtualThreadExecutor")
    public TaskExecutor virtualThreadExecutor(TaskDecorator contextPropagatingDecorator) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var adapter = new TaskExecutorAdapter(executor);
        adapter.setTaskDecorator(contextPropagatingDecorator);
        return adapter;
    }

    /**
     * 文件处理任务专用线程池（Platform Thread）。
     * 用于处理文件存证、上链和 MinIO 存储等 CPU 密集型操作。
     * 已配置 TaskDecorator 以传递 MDC 和 TenantContext。
     * <p>
     * 保留平台线程池的原因：
     * <ul>
     *   <li>CPU 密集型任务（加密/压缩）不适合虚拟线程</li>
     *   <li>需要限制并发数以控制资源使用</li>
     *   <li>CallerRunsPolicy 可在过载时提供背压</li>
     * </ul>
     * </p>
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
     * 通用异步任务线程池（Platform Thread）。
     * 用于一般性异步操作，已配置上下文传递。
     * <p>
     * 适用于需要控制并发数的后台任务。
     * </p>
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