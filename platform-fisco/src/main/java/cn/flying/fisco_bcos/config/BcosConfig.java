package cn.flying.fisco_bcos.config;

import lombok.Data;
import org.fisco.bcos.sdk.v3.config.model.AmopTopic;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "bcos")
public class BcosConfig {
    private Map<String, Object> cryptoMaterial;
    public Map<String, Object> network;
    public List<AmopTopic> amop;
    public Map<String, Object> account;
    public Map<String, Object> threadPool;
}
