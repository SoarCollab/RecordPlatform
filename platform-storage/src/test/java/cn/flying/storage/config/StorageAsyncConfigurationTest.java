package cn.flying.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageAsyncConfiguration Tests")
class StorageAsyncConfigurationTest {

    /**
     * 验证上传执行器使用有界线程池和有界队列，避免无界任务创建。
     */
    @Test
    @DisplayName("Should create bounded upload executor")
    void shouldCreateBoundedUploadExecutor() {
        StorageAsyncConfiguration configuration = new StorageAsyncConfiguration();

        ExecutorService executorService = configuration.storageUploadExecutor();

        try {
            assertThat(executorService).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor executor = (ThreadPoolExecutor) executorService;
            assertThat(executor.getCorePoolSize()).isEqualTo(8);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(32);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(256);
        } finally {
            executorService.close();
        }
    }
}
