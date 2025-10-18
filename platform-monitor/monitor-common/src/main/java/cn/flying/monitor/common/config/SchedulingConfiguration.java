package cn.flying.monitor.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;

/**
 * 调度配置类
 * 配置定时任务执行器
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfiguration implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // 配置定时任务线程池
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(5, r -> {
            Thread thread = new Thread(r, "monitor-scheduler-");
            thread.setDaemon(true);
            return thread;
        }));
        
        log.info("配置定时任务调度器，线程池大小: 5");
    }
}