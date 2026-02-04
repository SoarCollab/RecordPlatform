package cn.flying.storage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class StorageAsyncConfiguration {

    @Bean(name = "storageUploadExecutor", destroyMethod = "close")
    public ExecutorService storageUploadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
