package cn.flying.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program: RecordPlatform
 * @description: MinIO配置类
 * @author: flyingcoding
 * @create: 2025-01-16 14:23
 */
@Slf4j
@Configuration
public class MinIOConfiguration {
    //minIO地址
    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient getMinioClient() {
        log.info("Init MinIO Client...");
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
