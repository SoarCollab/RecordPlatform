package cn.flying.identity.integration;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStaff;
import cn.dev33.satoken.context.mock.SaRequestForMock;
import cn.dev33.satoken.context.mock.SaResponseForMock;
import cn.dev33.satoken.context.mock.SaStorageForMock;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.EmbeddedRedisTestConfig;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.AccountService;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;

/**
 * 集成测试基类
 * 提供通用的测试环境配置和辅助方法
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@ContextConfiguration(classes = {EmbeddedRedisTestConfig.class})
@Transactional  // 每个测试方法执行后自动回滚
public abstract class BaseIntegrationTest {

    // 测试数据常量
    protected static final String TEST_USERNAME = "testuser";
    protected static final String TEST_EMAIL = "test@example.com";
    protected static final String TEST_PASSWORD = "Test123456";
    protected static final String TEST_PHONE = "13800138000";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected AccountMapper accountMapper;

    @Autowired
    protected AccountService accountService;

    protected Account testAccount;

    @BeforeEach
    public void baseSetUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        // 清理Sa-Token会话
        try {
            StpUtil.logout();
        } catch (SaTokenContextException ignore) {
            // 无上下文时直接忽略
        }

        // 创建测试用户
        testAccount = createTestAccount(TEST_USERNAME, TEST_EMAIL, TEST_PASSWORD);
    }

    /**
     * 创建测试账户
     */
    protected Account createTestAccount(String username, String email, String password) {
        Account account = new Account();
        account.setId(IdUtil.getSnowflakeNextId());
        account.setUsername(username);
        account.setEmail(email);
        account.setPassword(accountService.encodePassword(password));
        account.setRole("user");
        account.setRegisterTime(LocalDateTime.now());
        account.setDeleted(0);

        accountMapper.insert(account);
        return account;
    }

    @AfterEach
    public void baseTearDown() {
        // 清理测试数据
        try {
            StpUtil.logout();
        } catch (SaTokenContextException ignore) {
            // 无上下文可忽略
        }
    }

    /**
     * 创建管理员账户
     */
    protected Account createAdminAccount(String username, String email) {
        Account account = new Account();
        account.setId(IdUtil.getSnowflakeNextId());
        account.setUsername(username);
        account.setEmail(email);
        account.setPassword(accountService.encodePassword(TEST_PASSWORD));
        account.setRole("admin");
        account.setRegisterTime(LocalDateTime.now());
        account.setDeleted(0);

        accountMapper.insert(account);
        return account;
    }

    /**
     * 登录测试用户
     */
    protected void loginAsTestUser() {
        prepareSaTokenContext();
        StpUtil.login(testAccount.getId());
    }

    /**
     * 登录指定用户
     */
    protected void loginAs(Long userId) {
        prepareSaTokenContext();
        StpUtil.login(userId);
    }

    /**
     * 获取认证Token
     */
    protected String getAuthToken(Long userId) {
        prepareSaTokenContext();
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    /**
     * 转换对象为JSON字符串
     */
    protected String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    /**
     * 准备 Sa-Token 线程本地上下文
     */
    private void prepareSaTokenContext() {
        SaRequestForMock request = new SaRequestForMock();
        request.requestPath = "/";
        SaTokenContextForThreadLocalStaff.setModelBox(
                request,
                new SaResponseForMock(),
                new SaStorageForMock()
        );
    }

    /**
     * 获取测试环境的 Sa-Token 头部名称
     */
    protected String getTokenHeaderName() {
        return SaManager.getConfig().getTokenName();
    }

    /**
     * 为请求添加认证 Token 头部
     *
     * @param builder 请求构造器
     * @param token   登录后获取的 Token 值
     * @return 已附加 Token 的构造器
     */
    protected MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(getTokenHeaderName(), token);
    }

    /**
     * 安全读取响应体
     */
    protected String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            return e.toString();
        }
    }
}
