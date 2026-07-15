package cn.flying.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 公共限流可信代理配置。
 */
@Component
@ConfigurationProperties(prefix = "spring.web.rate-limit.client-ip")
public class RateLimitClientIpProperties {

    private String trustedProxies = "";

    /**
     * 获取逗号分隔的可信代理数字 IP/CIDR 配置。
     */
    public String getTrustedProxies() {
        return trustedProxies;
    }

    /**
     * 设置逗号分隔的可信代理数字 IP/CIDR 配置。
     */
    public void setTrustedProxies(String trustedProxies) {
        this.trustedProxies = trustedProxies == null ? "" : trustedProxies;
    }
}
