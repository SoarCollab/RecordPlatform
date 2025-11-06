package cn.flying.identity.config;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme.Type;
import jakarta.annotation.Resource;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

import java.util.*;
import java.util.stream.Collectors;

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

    @Resource
    private AuthWhitelistProperties authWhitelistProperties;

    @Value("${swagger.contact.url:https://platform.flyingcoding.cn}")
    private String contactUrl;

    @Value("${swagger.license.url:https://www.apache.org/licenses/LICENSE-2.0}")
    private String licenseUrl;

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";

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
                        .addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
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
        AntPathMatcher pathMatcher = new AntPathMatcher();
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            List<String> excludePaths = new ArrayList<>();
            excludePaths.addAll(authWhitelistProperties.getPublicApiPatterns());
            excludePaths.addAll(authWhitelistProperties.getFederationPatterns());

            List<String> pathsToRemove = new ArrayList<>();

            openApi.getPaths().forEach((path, pathItem) -> {
                for (PathItem.HttpMethod method : PathItem.HttpMethod.values()) {
                    Operation operation = pathItem.readOperationsMap().get(method);
                    if (operation == null) {
                        continue;
                    }

                    if (Boolean.TRUE.equals(operation.getDeprecated())) {
                        pathItem.operation(method, null);
                        continue;
                    }

                    boolean isExcluded = excludePaths.stream()
                            .anyMatch(pattern -> pathMatcher.match(pattern, path));
                    if (isExcluded) {
                        operation.setSecurity(Collections.emptyList());
                    } else {
                        ensureBearerSecurity(operation);
                    }
                }

                if (pathItem.readOperations().isEmpty()) {
                    pathsToRemove.add(path);
                }
            });

            pathsToRemove.forEach(path -> openApi.getPaths().remove(path));
        };
    }

    /**
     * 基于 Sa-Token 注解补充权限与角色说明，并为 Deprecated 方法打标。
     */
    @Bean
    public OperationCustomizer permissionOperationCustomizer() {
        return (operation, handlerMethod) -> {
            if (handlerMethod.getMethod().isAnnotationPresent(Deprecated.class)
                    || handlerMethod.getBeanType().isAnnotationPresent(Deprecated.class)) {
                operation.setDeprecated(true);
            }

            List<String> permissions = resolvePermissions(handlerMethod);
            List<String> roles = resolveRoles(handlerMethod);
            boolean requiresLogin = resolveRequiresLogin(handlerMethod, permissions, roles);

            if (!permissions.isEmpty()) {
                operation.addExtension("x-required-permissions", permissions);
            }
            if (!roles.isEmpty()) {
                operation.addExtension("x-required-roles", roles);
            }
            if (requiresLogin) {
                operation.addExtension("x-requires-login", true);
            }

            updateDescription(operation, permissions, roles, requiresLogin);
            return operation;
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

    private void ensureBearerSecurity(Operation operation) {
        List<io.swagger.v3.oas.models.security.SecurityRequirement> security = operation.getSecurity();
        boolean hasBearer = security != null && security.stream()
                .anyMatch(req -> req.containsKey(SECURITY_SCHEME_NAME));
        if (!hasBearer) {
            operation.addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList(SECURITY_SCHEME_NAME));
        }
    }

    private List<String> resolvePermissions(HandlerMethod handlerMethod) {
        Set<String> permissions = new LinkedHashSet<>();
        SaCheckPermission methodAnnotation = handlerMethod.getMethodAnnotation(SaCheckPermission.class);
        if (methodAnnotation != null) {
            permissions.addAll(Arrays.stream(methodAnnotation.value())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList()));
        }
        SaCheckPermission typeAnnotation = handlerMethod.getBeanType().getAnnotation(SaCheckPermission.class);
        if (typeAnnotation != null) {
            permissions.addAll(Arrays.stream(typeAnnotation.value())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList()));
        }
        return new ArrayList<>(permissions);
    }

    private List<String> resolveRoles(HandlerMethod handlerMethod) {
        Set<String> roles = new LinkedHashSet<>();
        SaCheckRole methodAnnotation = handlerMethod.getMethodAnnotation(SaCheckRole.class);
        if (methodAnnotation != null) {
            roles.addAll(Arrays.stream(methodAnnotation.value())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList()));
        }
        SaCheckRole typeAnnotation = handlerMethod.getBeanType().getAnnotation(SaCheckRole.class);
        if (typeAnnotation != null) {
            roles.addAll(Arrays.stream(typeAnnotation.value())
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList()));
        }
        return new ArrayList<>(roles);
    }

    private boolean resolveRequiresLogin(HandlerMethod handlerMethod, List<String> permissions, List<String> roles) {
        return handlerMethod.hasMethodAnnotation(SaCheckLogin.class)
                || handlerMethod.getBeanType().isAnnotationPresent(SaCheckLogin.class)
                || !permissions.isEmpty()
                || !roles.isEmpty();
    }

    private void updateDescription(Operation operation, List<String> permissions,
                                   List<String> roles, boolean requiresLogin) {
        List<String> segments = new ArrayList<>();
        if (!permissions.isEmpty()) {
            segments.add("所需权限：" + String.join("、", permissions));
        }
        if (!roles.isEmpty()) {
            segments.add("所需角色：" + String.join("、", roles));
        }
        if (requiresLogin && permissions.isEmpty() && roles.isEmpty()) {
            segments.add("需要登录认证");
        }

        if (segments.isEmpty()) {
            return;
        }

        String description = operation.getDescription();
        if (description == null) {
            description = "";
        }
        if (description.contains("权限说明：")) {
            return;
        }

        String metadata = "**权限说明：** " + String.join("；", segments);
        if (StringUtils.hasText(description)) {
            operation.setDescription(description + "\n\n" + metadata);
        } else {
            operation.setDescription(metadata);
        }
    }
}
