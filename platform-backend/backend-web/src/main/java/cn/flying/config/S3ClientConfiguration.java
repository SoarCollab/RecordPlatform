package cn.flying.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * S3 兼容对象存储客户端配置类
 *
 * @author flyingcoding
 * @create: 2025-01-16 14:23
 */
@Slf4j
@Configuration
public class S3ClientConfiguration {
    // S3 兼容存储地址
    @Value("${s3.endpoint}")
    private String endpoint;

    @Value("${s3.access-key}")
    private String accessKey;

    @Value("${s3.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient s3Client() {
        log.info("Init S3 Compatible Storage Client...");
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
