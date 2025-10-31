package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 身份模块安全配置
 * 用于集中管理 CORS 策略等通用安全参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.identity.security")
public class IdentitySecurityProperties {

    /**
     * 允许访问的固定来源地址（完全匹配）。
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * 允许访问的来源模式（支持通配符）。
     */
    private List<String> allowedOriginPatterns = new ArrayList<>();

    /**
     * 是否允许跨域请求携带 Cookie 等凭证。
     */
    private boolean allowCredentials = false;

    /**
     * 预检请求缓存时间（秒）。
     */
    private long maxAge = 3600L;
}
