package cn.flying.identity.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AuthWhitelistProperties 测试
 * 验证白名单聚合与自定义覆盖能力
 */
class AuthWhitelistPropertiesTest {

    @Test
    void getAllPublicPatterns_shouldDeduplicateAndMergeCustomLists() {
        AuthWhitelistProperties properties = new AuthWhitelistProperties();
        properties.setPublicApiPatterns(new ArrayList<>(List.of("/public/api", "/api/auth/login")));
        properties.setDocumentationPatterns(new ArrayList<>(List.of("/swagger-ui/**", "/swagger-ui/**")));
        properties.setGatewayBypassPatterns(new ArrayList<>(List.of("/proxy/**")));

        List<String> result = properties.getAllPublicPatterns();

        Assertions.assertTrue(result.contains("/public/api"));
        Assertions.assertTrue(result.contains("/swagger-ui/**"));
        Assertions.assertTrue(result.contains("/proxy/**"));
        Assertions.assertEquals(result.size(), new HashSet<>(result).size(), "白名单聚合后应去重");
    }

    @Test
    void getAllPublicPatterns_shouldSupportEmptySections() {
        AuthWhitelistProperties properties = new AuthWhitelistProperties();
        properties.setPublicApiPatterns(new ArrayList<>());
        properties.setFederationPatterns(new ArrayList<>());
        properties.setDocumentationPatterns(new ArrayList<>());
        properties.setInfrastructurePatterns(new ArrayList<>());
        properties.setStaticAssetPatterns(new ArrayList<>());
        properties.setGatewayBypassPatterns(new ArrayList<>());
        properties.setErrorPagePatterns(new ArrayList<>(List.of("/error")));

        List<String> result = properties.getAllPublicPatterns();

        Assertions.assertEquals(List.of("/error"), result, "仅保留 error 路径时应正确返回");
    }
}
