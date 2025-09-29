package cn.flying.identity.integration;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 集成测试基类
 * 提供通用的测试环境配置和辅助方法
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
    protected ObjectMapper objectMapper;

    @Autowired
    protected AccountMapper accountMapper;

    @Autowired
    protected AccountService accountService;

    protected Account testAccount;

    @BeforeEach
    public void baseSetUp() {
        // 清理Sa-Token会话
        StpUtil.logout();

        // 创建测试用户
        testAccount = createTestAccount(TEST_USERNAME, TEST_EMAIL, TEST_PASSWORD);
    }

    /**
     * 创建测试账户
     */
    protected Account createTestAccount(String username, String email, String password) {
        Account account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        account.setPassword(accountService.encodePassword(password));
        account.setRole("USER");
        account.setRegisterTime(LocalDateTime.now());
        account.setDeleted(0);

        accountMapper.insert(account);
        return account;
    }

    @AfterEach
    public void baseTearDown() {
        // 清理测试数据
        StpUtil.logout();
    }

    /**
     * 创建管理员账户
     */
    protected Account createAdminAccount(String username, String email) {
        Account account = new Account();
        account.setUsername(username);
        account.setEmail(email);
        account.setPassword(accountService.encodePassword(TEST_PASSWORD));
        account.setRole("ADMIN");
        account.setRegisterTime(LocalDateTime.now());
        account.setDeleted(0);

        accountMapper.insert(account);
        return account;
    }

    /**
     * 登录测试用户
     */
    protected void loginAsTestUser() {
        StpUtil.login(testAccount.getId());
    }

    /**
     * 登录指定用户
     */
    protected void loginAs(Long userId) {
        StpUtil.login(userId);
    }

    /**
     * 获取认证Token
     */
    protected String getAuthToken(Long userId) {
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    /**
     * 转换对象为JSON字符串
     */
    protected String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}
