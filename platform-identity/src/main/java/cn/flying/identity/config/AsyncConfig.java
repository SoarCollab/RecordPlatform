package cn.flying.identity.config;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务配置
 * 用于支持异步操作日志记录等功能
 *
 * @author 王贝强
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Resource
    private AsyncTaskConfig asyncTaskConfig;

    /**
     * 异步任务执行器
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 使用配置文件中的值
        executor.setCorePoolSize(asyncTaskConfig.getCorePoolSize());
        executor.setMaxPoolSize(asyncTaskConfig.getMaxPoolSize());
        executor.setQueueCapacity(asyncTaskConfig.getQueueCapacity());
        executor.setThreadNamePrefix(asyncTaskConfig.getThreadNamePrefix());
        executor.setKeepAliveSeconds(asyncTaskConfig.getKeepAliveSeconds());
        executor.setAwaitTerminationSeconds(asyncTaskConfig.getAwaitTerminationSeconds());

        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务结束后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);

        executor.initialize();
        return executor;
    }
}
