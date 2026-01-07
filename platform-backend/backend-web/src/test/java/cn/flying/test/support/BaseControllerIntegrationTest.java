package cn.flying.test.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.flying.test.BaseIntegrationTest;

@AutoConfigureMockMvc
public abstract class BaseControllerIntegrationTest extends BaseIntegrationTest {

    protected static final String HEADER_TENANT_ID = "X-Tenant-ID";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected Long testUserId = JwtTestSupport.DEFAULT_USER_ID;
    protected Long testTenantId = JwtTestSupport.DEFAULT_TENANT_ID;
    protected String testToken = JwtTestSupport.generateToken();

    protected void setTestUser(Long userId, Long tenantId) {
        this.testUserId = userId;
        this.testTenantId = tenantId;
        this.testToken = JwtTestSupport.generateToken(userId, "testuser_" + userId, "user", tenantId);
    }

    protected void setTestAdmin(Long userId, Long tenantId) {
        this.testUserId = userId;
        this.testTenantId = tenantId;
        this.testToken = JwtTestSupport.generateAdminToken(userId, tenantId);
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
        return builder
                .header("Authorization", "Bearer " + testToken)
                .header(HEADER_TENANT_ID, testTenantId);
    }

    protected MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder, String token, Long tenantId) {
        return builder
                .header("Authorization", "Bearer " + token)
                .header(HEADER_TENANT_ID, tenantId);
    }

    protected ResultActions performGet(String url) throws Exception {
        return mockMvc.perform(withAuth(get(url)));
    }

    protected ResultActions performGet(String url, Object... uriVars) throws Exception {
        return mockMvc.perform(withAuth(get(url, uriVars)));
    }

    protected ResultActions performPost(String url, Object body) throws Exception {
        return mockMvc.perform(withAuth(post(url))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions performPut(String url, Object body) throws Exception {
        return mockMvc.perform(withAuth(put(url))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions performDelete(String url) throws Exception {
        return mockMvc.perform(withAuth(delete(url)));
    }

    protected ResultActions performDelete(String url, Object... uriVars) throws Exception {
        return mockMvc.perform(withAuth(delete(url, uriVars)));
    }

    protected ResultActions expectOk(ResultActions actions) throws Exception {
        return actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    protected ResultActions expectCreated(ResultActions actions) throws Exception {
        return actions.andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    protected ResultActions expectBadRequest(ResultActions actions) throws Exception {
        return actions.andExpect(status().isBadRequest());
    }

    protected ResultActions expectUnauthorized(ResultActions actions) throws Exception {
        return actions.andExpect(status().isUnauthorized());
    }

    protected ResultActions expectForbidden(ResultActions actions) throws Exception {
        return actions.andExpect(status().isForbidden());
    }

    protected ResultActions expectNotFound(ResultActions actions) throws Exception {
        return actions.andExpect(status().isNotFound());
    }

    protected <T> T extractData(MvcResult result, Class<T> clazz) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        JsonNode data = root.get("data");
        return objectMapper.treeToValue(data, clazz);
    }

    protected String extractDataAsString(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        JsonNode data = root.get("data");
        return data != null ? data.toString() : null;
    }

    protected int extractCode(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("code").asInt();
    }

    protected String extractMessage(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(content);
        return root.get("message").asText();
    }

    protected ResultActions printResponse(ResultActions actions) throws Exception {
        return actions.andDo(print());
    }
}
