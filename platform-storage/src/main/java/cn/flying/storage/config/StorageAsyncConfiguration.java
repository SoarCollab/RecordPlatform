package cn.flying.storage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class StorageAsyncConfiguration {

    /**
     * 创建有界上传线程池，限制并发和排队长度，避免副本上传被无界虚拟线程放大为存储 DoS。
     */
    @Bean(name = "storageUploadExecutor", destroyMethod = "close")
    public ExecutorService storageUploadExecutor() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task);
            thread.setName("storage-upload-" + threadIndex.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8,
                32,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
