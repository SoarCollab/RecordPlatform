package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步任务配置属性类
 * 管理异步任务执行器的相关配置
 *
 * @author 王贝强
 */
@Data
@Component
@ConfigurationProperties(prefix = "async.task")
public class AsyncTaskConfig {
    
    /**
     * 核心线程数
     */
    private int corePoolSize = 5;
    
    /**
     * 最大线程数
     */
    private int maxPoolSize = 20;
    
    /**
     * 队列容量
     */
    private int queueCapacity = 100;
    
    /**
     * 线程名前缀
     */
    private String threadNamePrefix = "async-task-";
    
    /**
     * 线程空闲时间（秒）
     */
    private int keepAliveSeconds = 60;
    
    /**
     * 等待时间（秒）
     */
    private int awaitTerminationSeconds = 60;
}
