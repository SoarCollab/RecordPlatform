package cn.flying.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OAuth2客户端服务
 * 封装与Identity服务的OAuth2交互逻辑，实现标准的OAuth2授权码模式
 *
 * @author Claude Code
 * @since 2025-01-16
 */
@Slf4j
@Service
public class OAuth2ClientService {

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${oauth2.client.client-id}")
    private String clientId;

    @Value("${oauth2.client.client-secret}")
    private String clientSecret;

    @Value("${oauth2.client.redirect-uri}")
    private String redirectUri;

    @Value("${oauth2.client.authorization-uri}")
    private String authorizationUri;

    @Value("${oauth2.client.token-uri}")
    private String tokenUri;

    @Value("${oauth2.client.scope:read,write}")
    private String scope;

    /**
     * 生成授权URL
     * 用户首次访问时，重定向到此URL进行OAuth2授权
     *
     * @param state CSRF防护的state参数
     * @return 完整的授权URL
     */
    public String getAuthorizationUrl(String state) {
        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("scope", scope);
        params.put("state", state);
        params.put("response_type", "code");

        String queryString = params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + urlEncode(entry.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");

        String fullUrl = authorizationUri + "?" + queryString;
        log.debug("生成授权URL: {}", fullUrl);
        return fullUrl;
    }

    /**
     * URL编码辅助方法
     */
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * 用授权码换取访问令牌
     * 这是OAuth2授权码模式的核心步骤
     *
     * @param code         授权码
     * @param codeVerifier PKCE验证码（可选，当前实现不使用PKCE）
     * @return Token响应
     */
    public TokenResponse exchangeCodeForToken(String code, String codeVerifier) {
        try {
            log.info("使用授权码换取访问令牌: code={}", code.substring(0, Math.min(8, code.length())) + "...");

            // 构建请求体
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("grant_type", "authorization_code");
            requestBody.add("code", code);
            requestBody.add("redirect_uri", redirectUri);
            requestBody.add("client_id", clientId);
            requestBody.add("client_secret", clientSecret);

            if (codeVerifier != null && !codeVerifier.isEmpty()) {
                requestBody.add("code_verifier", codeVerifier);
            }

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUri, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("换取访问令牌失败: status={}", response.getStatusCode());
                return TokenResponse.error("Token exchange failed with status: " + response.getStatusCode());
            }

            // 解析响应
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                log.error("Token响应体为空");
                return TokenResponse.error("Empty token response");
            }

            JsonNode root = objectMapper.readTree(responseBody);
            int resultCode = root.path("code").asInt(0);

            if (resultCode != 1) {
                String message = root.path("message").asText("Token exchange failed");
                log.error("Identity返回错误: code={}, message={}", resultCode, message);
                return TokenResponse.error(message);
            }

            // 提取token数据
            JsonNode data = root.path("data");
            String accessToken = data.path("access_token").asText();
            String refreshToken = data.path("refresh_token").asText();
            String tokenType = data.path("token_type").asText("Bearer");
            int expiresIn = data.path("expires_in").asInt(7200);
            String tokenScope = data.path("scope").asText(scope);

            if (accessToken.isEmpty()) {
                log.error("访问令牌为空");
                return TokenResponse.error("Access token is empty");
            }

            TokenResponse tokenResponse = TokenResponse.success(
                    accessToken, refreshToken, tokenType, expiresIn, tokenScope
            );

            log.info("成功获取访问令牌: expiresIn={}, scope={}", expiresIn, tokenScope);
            return tokenResponse;

        } catch (HttpClientErrorException e) {
            log.error("Token请求失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            return TokenResponse.error("Token request failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("换取访问令牌异常", e);
            return TokenResponse.error("Token exchange error: " + e.getMessage());
        }
    }

    /**
     * 刷新访问令牌
     * 当access_token过期时，使用refresh_token获取新的access_token
     *
     * @param refreshToken 刷新令牌
     * @return Token响应
     */
    public TokenResponse refreshAccessToken(String refreshToken) {
        try {
            log.info("刷新访问令牌");

            // 构建请求体
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("grant_type", "refresh_token");
            requestBody.add("refresh_token", refreshToken);
            requestBody.add("client_id", clientId);
            requestBody.add("client_secret", clientSecret);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUri, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("刷新令牌失败: status={}", response.getStatusCode());
                return TokenResponse.error("Token refresh failed with status: " + response.getStatusCode());
            }

            // 解析响应
            String responseBody = response.getBody();
            JsonNode root = objectMapper.readTree(responseBody);
            int resultCode = root.path("code").asInt(0);

            if (resultCode != 1) {
                String message = root.path("message").asText("Token refresh failed");
                log.error("刷新令牌失败: message={}", message);
                return TokenResponse.error(message);
            }

            // 提取token数据
            JsonNode data = root.path("data");
            String newAccessToken = data.path("access_token").asText();
            String newRefreshToken = data.path("refresh_token").asText();
            String tokenType = data.path("token_type").asText("Bearer");
            int expiresIn = data.path("expires_in").asInt(7200);
            String tokenScope = data.path("scope").asText(scope);

            TokenResponse tokenResponse = TokenResponse.success(
                    newAccessToken, newRefreshToken, tokenType, expiresIn, tokenScope
            );

            log.info("成功刷新访问令牌");
            return tokenResponse;

        } catch (Exception e) {
            log.error("刷新访问令牌异常", e);
            return TokenResponse.error("Token refresh error: " + e.getMessage());
        }
    }

    /**
     * 撤销令牌
     * 用户登出时调用，撤销access_token和refresh_token
     *
     * @param token     要撤销的令牌
     * @param tokenType 令牌类型（access_token或refresh_token）
     * @return 是否成功
     */
    public boolean revokeToken(String token, String tokenType) {
        try {
            log.info("撤销令牌: tokenType={}", tokenType);

            // 构建请求体
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("token", token);
            requestBody.put("token_type_hint", tokenType);
            requestBody.put("client_id", clientId);
            requestBody.put("client_secret", clientSecret);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求到revoke端点
            String revokeUri = tokenUri.replace("/tokens", "/tokens/revoke");
            ResponseEntity<String> response = restTemplate.postForEntity(revokeUri, entity, String.class);

            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                log.info("令牌撤销成功");
            } else {
                log.warn("令牌撤销失败: status={}", response.getStatusCode());
            }

            return success;

        } catch (Exception e) {
            log.error("撤销令牌异常", e);
            return false;
        }
    }

    /**
     * Token响应数据类
     */
    @Data
    public static class TokenResponse implements Serializable {
        private boolean success;
        private String accessToken;
        private String refreshToken;
        private String tokenType;
        private int expiresIn;
        private String scope;
        private String errorMessage;

        public static TokenResponse success(String accessToken, String refreshToken,
                                            String tokenType, int expiresIn, String scope) {
            TokenResponse response = new TokenResponse();
            response.setSuccess(true);
            response.setAccessToken(accessToken);
            response.setRefreshToken(refreshToken);
            response.setTokenType(tokenType);
            response.setExpiresIn(expiresIn);
            response.setScope(scope);
            return response;
        }

        public static TokenResponse error(String errorMessage) {
            TokenResponse response = new TokenResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }
}
