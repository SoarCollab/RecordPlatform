package cn.flying.identity.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme.Type;
import jakarta.annotation.Resource;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger配置类
 * 配置API文档的基本信息和展示内容
 */
@Configuration
@SecurityScheme(
        type = SecuritySchemeType.HTTP,
        scheme = "Bearer",
        name = "Bearer Authentication",
        in = SecuritySchemeIn.HEADER,
        description = "请在请求头中添加 Authorization: Bearer {token}"
)
@OpenAPIDefinition(security = {@SecurityRequirement(name = "Bearer Authentication")})
public class SwaggerConfig {

    @Resource
    private ApplicationProperties applicationProperties;

    @Value("${swagger.contact.url:https://platform.flyingcoding.cn}")
    private String contactUrl;

    @Value("${swagger.license.url:https://www.apache.org/licenses/LICENSE-2.0}")
    private String licenseUrl;

    /**
     * 创建OpenAPI配置
     *
     * @return OpenAPI配置对象
     */
    @Bean
    public OpenAPI customOpenAPI() {
        ApplicationProperties.AppInfo appInfo = applicationProperties.getAppInfo();

        return new OpenAPI()
                .info(new Info()
                        .title("Platform Identity API")
                        .version(appInfo.getVersion())
                        .description(buildApiDescription())
                        .contact(new Contact()
                                .name("Platform Team")
                                .email("wbq124593655@gmail.com")
                                .url(contactUrl))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url(licenseUrl)))
                // 全局添加安全要求
                .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement()
                        .addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new io.swagger.v3.oas.models.security.SecurityScheme()
                                        .type(Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("请在请求头中添加 Authorization: Bearer {token}")));
    }

    /**
     * 全局OpenAPI自定义配置
     * 为所有接口自动添加安全要求，除了明确排除的接口
     */
    @Bean
    public OpenApiCustomizer globalSecurityCustomizer() {
        return openApi -> {
            // 不需要认证的接口路径
            String[] excludePaths = {
                    "/api/auth/login",
                    "/api/auth/register", 
                    "/api/auth/verify-code",
                    "/api/auth/reset-password",
                    "/api/auth/signin",
                    "/api/auth/signup",
                    "/api/verify/email/send",
                    "/api/verify/image/generate",
                    "/api/verify/image/verify",
                    "/api/verify/check-limit",
                    "/api/verify/config",
                    "/oauth/authorize",
                    "/oauth/token",
                    "/oauth/userinfo"
            };

            if (openApi.getPaths() != null) {
                openApi.getPaths().forEach((path, pathItem) -> {
                    boolean isExcluded = false;
                    for (String excludePath : excludePaths) {
                        if (path.equals(excludePath)) {
                            isExcluded = true;
                            break;
                        }
                    }
                    
                    // 为非排除的接口添加安全要求
                    if (!isExcluded) {
                        io.swagger.v3.oas.models.security.SecurityRequirement securityRequirement = 
                            new io.swagger.v3.oas.models.security.SecurityRequirement()
                                .addList("Bearer Authentication");
                        
                        if (pathItem.getGet() != null) {
                            pathItem.getGet().addSecurityItem(securityRequirement);
                        }
                        if (pathItem.getPost() != null) {
                            pathItem.getPost().addSecurityItem(securityRequirement);
                        }
                        if (pathItem.getPut() != null) {
                            pathItem.getPut().addSecurityItem(securityRequirement);
                        }
                        if (pathItem.getDelete() != null) {
                            pathItem.getDelete().addSecurityItem(securityRequirement);
                        }
                        if (pathItem.getPatch() != null) {
                            pathItem.getPatch().addSecurityItem(securityRequirement);
                        }
                    }
                });
            }
        };
    }

    /**
     * 构建API描述信息
     */
    private String buildApiDescription() {
        return """
                ## 存证平台认证服务 API 文档
                
                ### 🔐 认证方式
                本API使用SA-Token进行身份认证，请在请求头中添加：
                ```
                Authorization: Bearer {your-token}
                ```
                
                ### 📋 错误码说明
                | 错误码范围 | 说明 |
                |-----------|------|
                | 1 | 成功 |
                | 10001-19999 | 参数错误 |
                | 20001-29999 | 用户错误 |
                | 30001-39999 | 业务错误 |
                | 40001-49999 | 系统错误 |
                | 50001-59999 | 数据错误 |
                | 60001-69999 | SSO和OAuth错误 |
                | 70001-79999 | 权限错误 |
                | 90001-99999 | 系统繁忙 |
                
                ### 🔄 主要功能
                - **用户认证**: 注册、登录、登出
                - **OAuth2.0**: 授权码模式、客户端凭证模式
                - **SSO单点登录**: 跨域单点登录支持
                - **第三方登录**: GitHub、Google、微信等
                - **权限管理**: 基于角色的权限控制
                - **监控审计**: 操作日志、流量监控
                
                """;
    }
}