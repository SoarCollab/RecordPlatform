package cn.flying.monitor.client.config;

import cn.flying.monitor.client.task.MonitorJobBean;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enhanced Quartz configuration with configurable collection intervals
 */
@Slf4j
@Configuration
public class QuartzConfiguration {

    @Value("${monitor.client.metrics.collection-interval:10}")
    private int collectionIntervalSeconds;

    @Value("${monitor.client.metrics.enable-scheduling:true}")
    private boolean enableScheduling;

    @Bean
    public JobDetail jobDetailFactoryBean() {
        log.info("Creating monitor job with collection interval: {} seconds", collectionIntervalSeconds);
        
        return JobBuilder.newJob(MonitorJobBean.class)
                .withIdentity("monitor-task")
                .withDescription("Enhanced monitoring task with configurable intervals")
                .storeDurably()
                .build();
    }

    @Bean
    public Trigger cronTriggerFactoryBean(JobDetail detail) {
        if (!enableScheduling) {
            log.info("Monitoring scheduling is disabled");
            return null;
        }

        // Create cron expression based on collection interval
        String cronExpression = createCronExpression(collectionIntervalSeconds);
        log.info("Creating monitor trigger with cron expression: {}", cronExpression);

        CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule(cronExpression)
                .withMisfireHandlingInstructionDoNothing(); // Don't execute missed jobs

        return TriggerBuilder.newTrigger()
                .forJob(detail)
                .withIdentity("monitor-trigger")
                .withDescription("Configurable monitoring trigger")
                .withSchedule(cron)
                .startNow()
                .build();
    }

    /**
     * Create cron expression based on interval in seconds
     */
    private String createCronExpression(int intervalSeconds) {
        if (intervalSeconds < 1) {
            log.warn("Collection interval too small ({}s), using minimum of 1 second", intervalSeconds);
            intervalSeconds = 1;
        }

        if (intervalSeconds >= 60) {
            // For intervals >= 60 seconds, use minute-based scheduling
            int intervalMinutes = intervalSeconds / 60;
            if (intervalMinutes >= 60) {
                // For intervals >= 1 hour, use hour-based scheduling
                int intervalHours = intervalMinutes / 60;
                return String.format("0 0 */%d * * ?", intervalHours);
            } else {
                return String.format("0 */%d * * * ?", intervalMinutes);
            }
        } else {
            // For intervals < 60 seconds, use second-based scheduling
            return String.format("*/%d * * * * ?", intervalSeconds);
        }
    }

    /**
     * Create additional trigger for high-frequency monitoring (if needed)
     */
    @Bean
    public Trigger highFrequencyTrigger(JobDetail detail) {
        // Only create high-frequency trigger if main interval is > 30 seconds
        if (!enableScheduling || collectionIntervalSeconds <= 30) {
            return null;
        }

        log.info("Creating high-frequency trigger for critical metrics");

        // High-frequency trigger for critical metrics every 5 seconds
        CronScheduleBuilder cron = CronScheduleBuilder.cronSchedule("*/5 * * * * ?")
                .withMisfireHandlingInstructionDoNothing();

        return TriggerBuilder.newTrigger()
                .forJob(detail)
                .withIdentity("monitor-high-frequency-trigger")
                .withDescription("High-frequency monitoring for critical metrics")
                .withSchedule(cron)
                .startNow()
                .build();
    }

    /**
     * Get current collection interval
     */
    public int getCollectionIntervalSeconds() {
        return collectionIntervalSeconds;
    }

    /**
     * Check if scheduling is enabled
     */
    public boolean isSchedulingEnabled() {
        return enableScheduling;
    }
}
