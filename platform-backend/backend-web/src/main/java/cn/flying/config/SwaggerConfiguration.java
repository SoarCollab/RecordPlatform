package cn.flying.config;

import cn.flying.common.constant.Result;
import cn.flying.dao.vo.auth.AuthorizeVO;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: Swagger配置类
 * @author flyingcoding
 * @create: 2025-01-16 12:02
 */
@Configuration
@SecurityScheme(type = SecuritySchemeType.HTTP, scheme = "Bearer",
        name = "Authorization", in = SecuritySchemeIn.HEADER)
@OpenAPIDefinition(security = { @SecurityRequirement(name = "Authorization") })
public class SwaggerConfiguration {

    /**
     * 配置文档介绍以及详细信息
     * @return OpenAPI
     */
    @Bean
    public OpenAPI springShopOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RecordPlatform 项目 API 文档")
                        .description("""
                                RecordPlatform API文档，按服务分组展示。

                                ## 服务架构
                                - **用户认证服务**: 用户注册、登录、密码管理
                                - **文件存储服务**: 文件上传/下载/分享（底层调用区块链存证+分布式存储）
                                - **消息通信服务**: 私信会话、消息管理、SSE推送
                                - **系统管理服务**: 审计日志、权限管理、公告、工单

                                ## 底层服务说明
                                - **区块链服务 (FISCO BCOS)**: 文件存证、交易记录查询
                                - **分布式存储服务 (S3-compatible)**: 文件分片加密存储
                                """)
                        .version("1.0")
                        .contact( new Contact()
                                .name("flying")
                                .url("https://github.com/wbq123789/RecordPlatform"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")
                        )
                );
    }

    /**
     * 配置自定义的OpenApi相关信息
     * @return OpenApiCustomizer
     */
    @Bean
    public OpenApiCustomizer customerGlobalHeaderOpenApiCustomizer() {
        return api -> {
            this.authorizePathItems().forEach(api.getPaths()::addPathItem);
            this.removeDeprecatedPageFields(api);
            this.preserveNullablePlatformAuditResult(api);
        };
    }

    /**
     * Preserves a nullable object reference without modifying its shared component schema.
     * <p>
     * springdoc 3 输出 OpenAPI 3.1，`nullable` 标记不再生效：可空的 {@code $ref} 属性必须写成
     * {@code anyOf: [$ref, {type: "null"}]}，否则生成的客户端类型会错误地声明为非空。
     */
    private void preserveNullablePlatformAuditResult(io.swagger.v3.oas.models.OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return;
        }
        Schema<?> audit = api.getComponents().getSchemas().get("PlatformAuditVO");
        if (audit == null || audit.getProperties() == null || !audit.getProperties().containsKey("result")) {
            return;
        }
        Schema<Object> nullableResult = new Schema<>();
        nullableResult.setDescription("Recorded successful response; null before success or after failure");
        Schema<Object> nullBranch = new Schema<>();
        // OpenAPI 3.1 用 types 集合表达 null 分支，setType("null") 会被 swagger-core 丢弃
        nullBranch.addType("null");
        nullableResult.setAnyOf(List.of(
                new Schema<>().$ref("#/components/schemas/PlatformMutationVO"),
                nullBranch));
        audit.addProperty("result", nullableResult);
    }

    /**
     * 移除 MyBatis-Plus IPage 中已废弃的 pages 字段
     */
    private void removeDeprecatedPageFields(io.swagger.v3.oas.models.OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return;
        }
        api.getComponents().getSchemas().forEach((name, schema) -> {
            @SuppressWarnings("unchecked")
            Map<String, Schema<?>> properties = schema.getProperties();
            if (properties != null) {
                properties.remove("pages");
            }
        });
    }

    /**
     * 登录接口和退出登录接口手动添加一下
     * @return PathItems
     */
    /**
     * OpenAPI 3.1 的 schema 类型必须写入 types 集合，{@code type("string")} 在序列化时会被丢弃。
     *
     * @param format 可选的格式（如 password）
     * @return 声明为 string 的 schema
     */
    private static Schema<String> stringSchema(String format) {
        Schema<String> schema = new Schema<>();
        schema.addType("string");
        if (format != null) {
            schema.setFormat(format);
        }
        return schema;
    }

    private Map<String, PathItem> authorizePathItems(){
        Map<String, PathItem> map = new HashMap<>();
        // 登录接口
        map.put("/api/v1/auth/login", new PathItem()
                .post(new Operation()
                        .tags(List.of("登录校验相关"))
                        .summary("登录验证接口")
                        .description("用户登录接口，返回JWT令牌")
                        .addParametersItem(new Parameter()
                                .in("query")
                                .name("username")
                                .description("用户名")
                                .required(true)
                                .schema(stringSchema(null))
                        )
                        .addParametersItem(new Parameter()
                                .in("query")
                                .name("password")
                                .description("密码")
                                .required(true)
                                .schema(stringSchema("password"))
                        )
                        .responses(new ApiResponses()
                                .addApiResponse("200", new ApiResponse()
                                        .description("登录成功")
                                        .content(new Content().addMediaType("application/json", new MediaType()
                                                .example(Result.success(new AuthorizeVO()))
                                        ))
                                )
                                .addApiResponse("401", new ApiResponse()
                                        .description("登录失败")
                                        .content(new Content().addMediaType("application/json", new MediaType()
                                                .example(Result.error("用户名或密码错误"))
                                        ))
                                )
                        )
                )
        );

        // 退出登录接口
        map.put("/api/v1/auth/logout", new PathItem()
                .get(new Operation()
                        .tags(List.of("登录校验相关"))
                        .summary("退出登录接口")
                        .description("用户退出登录，使当前JWT令牌失效")
                        .responses(new ApiResponses()
                                .addApiResponse("200", new ApiResponse()
                                        .description("退出成功")
                                        .content(new Content().addMediaType("application/json", new MediaType()
                                                .example(Result.success())
                                        ))
                                )
                                .addApiResponse("401", new ApiResponse()
                                        .description("未授权")
                                        .content(new Content().addMediaType("application/json", new MediaType()
                                                .example(Result.error("用户未登录"))
                                        ))
                                )
                        )
                )
        );

        return map;
    }
}
