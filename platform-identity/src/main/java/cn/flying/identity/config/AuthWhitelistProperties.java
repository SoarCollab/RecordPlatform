package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 鉴权白名单配置
 * 统一管理无需登录即可访问的接口路径，支持通过配置追加或覆盖。
 * 配置前缀：platform.identity.whitelist
 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.identity.whitelist")
public class AuthWhitelistProperties {

    /**
     * 对外开放的 REST 接口路径（兼容历史命名）。
     */
    private List<String> publicApiPatterns = new ArrayList<>(List.of(
            "/api/auth/login",
            "/api/auth/signin",
            "/api/auth/register",
            "/api/auth/signup",
            "/api/auth/verify-code",
            "/api/auth/reset-password",
            "/api/auth/status",
            "/api/auth/sessions",
            "/api/auth/sessions/status",
            "/api/auth/sessions/current",
            "/api/auth/users",
            "/api/auth/verification-codes",
            "/api/auth/passwords/reset",
            "/api/auth/third-party/**",
            "/api/verification/**",
            "/api/verify/**"
    ));

    /**
     * OAuth/SSO 授权流程所需放行的路径。
     */
    private List<String> federationPatterns = new ArrayList<>(List.of(
            "/oauth/**",
            "/api/oauth/**",
            "/api/sso/**"
    ));

    /**
     * 文档及前端工具访问路径。
     */
    private List<String> documentationPatterns = new ArrayList<>(List.of(
            "/doc.html",
            "/swagger-ui/**",
            "/swagger-resources/**",
            "/v3/api-docs/**",
            "/webjars/**"
    ));

    /**
     * 基础设施与监控端点。
     */
    private List<String> infrastructurePatterns = new ArrayList<>(List.of(
            "/druid/**",
            "/actuator/**"
    ));

    /**
     * 静态资源路径。
     */
    private List<String> staticAssetPatterns = new ArrayList<>(List.of(
            "/static/**",
            "/favicon.ico",
            "/docs/**"
    ));

    /**
     * 网关代理与降级路径，由下游服务处理。
     */
    private List<String> gatewayBypassPatterns = new ArrayList<>(List.of(
            "/api/gateway/proxy/**",
            "/api/v1/**",
            "/api/v2/**",
            "/gateway/**"
    ));

    /**
     * 基础错误处理路径。
     */
    private List<String> errorPagePatterns = new ArrayList<>(List.of(
            "/error"
    ));

    /**
     * 聚合所有白名单路径。
     *
     * @return 去重后的白名单列表
     */
    public List<String> getAllPublicPatterns() {
        Set<String> combined = new HashSet<>();
        combined.addAll(publicApiPatterns);
        combined.addAll(federationPatterns);
        combined.addAll(documentationPatterns);
        combined.addAll(infrastructurePatterns);
        combined.addAll(staticAssetPatterns);
        combined.addAll(gatewayBypassPatterns);
        combined.addAll(errorPagePatterns);
        return new ArrayList<>(combined);
    }
}
