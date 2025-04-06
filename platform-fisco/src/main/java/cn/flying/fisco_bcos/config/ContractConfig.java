package cn.flying.fisco_bcos.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(
    prefix = "contract"
)
public class ContractConfig {
  private String sharingAddress;

  private String storageAddress;
}
