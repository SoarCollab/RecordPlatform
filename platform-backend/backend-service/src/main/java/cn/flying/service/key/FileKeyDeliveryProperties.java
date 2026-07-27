package cn.flying.service.key;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 下载密钥 grant 与旧协议迁移窗口配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "file.key-delivery")
public class FileKeyDeliveryProperties {

    private Duration grantTtl = Duration.ofSeconds(60);

    private Duration retryWindow = Duration.ofSeconds(10);

    private int maxSameSessionRetries = 1;

    private boolean legacyPlaintextEnabled;

    private Instant legacyPlaintextNotAfter = Instant.parse("2026-10-01T00:00:00Z");
}
