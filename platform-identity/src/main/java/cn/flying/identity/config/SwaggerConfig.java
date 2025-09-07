package cn.flying.identity.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger配置类
 * 配置API文档的基本信息和展示内容
 */
@Configuration
public class SwaggerConfig {

    @Resource
    private ApplicationProperties applicationProperties;

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
                                .url("https://platform.flyingcoding.cn"))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("请在请求头中添加 Authorization: Bearer {token}")));
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
                
                ### 📞 技术支持
                - **错误码文档**: [查看详细错误码说明](./docs/ERROR_CODES.md)
                - **配置文档**: [查看配置说明](./docs/CONFIGURATION.md)
                - **故障排除**: [查看故障排除指南](./docs/TROUBLESHOOTING.md)
                
                """;
    }
}