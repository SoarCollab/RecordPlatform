package cn.flying.backendTest;

import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.vo.auth.AccountVO;
import cn.flying.service.AccountService;
import cn.flying.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库集成测试（基于 Testcontainers MySQL/Redis/RabbitMQ）。
 * <p>
 * 目标：用最少用例验证关键基础设施可用（Flyway + MyBatis-Plus + AOP）。
 */
@Slf4j
public class DatabaseIT extends BaseIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * 构造数据库测试用账号实体（避免测试之间通过固定用户名耦合）。
     *
     * @param username 用户名
     * @return 账号实体
     */
    private Account buildTestAccount(String username) {
        Account account = new Account();
        account.setId(IdUtils.nextEntityId());
        account.setUsername(username);
        account.setPassword("123456");
        account.setRole("user");
        account.setEmail(username + "@test.local");
        return account;
    }

    /**
     * 验证 MyBatis-Plus 在测试环境下可完成插入与查询。
     */
    @Test
    @Transactional
    void shouldInsertAndQueryAccount() {
        String username = "it_user_" + IdUtils.nextEntityId();
        Account account = buildTestAccount(username);

        assertTrue(accountService.save(account));

        LambdaQueryWrapper<Account> wrapper = Wrappers.<Account>lambdaQuery()
                .eq(Account::getUsername, username);
        Account found = accountService.getOne(wrapper);

        assertNotNull(found);
        assertEquals(username, found.getUsername());
        assertNotNull(found.getId());
    }

    /**
     * 验证逻辑删除字段（deleted）是否生效：删除后按相同条件查询应返回 null。
     */
    @Test
    @Transactional
    void shouldApplyLogicalDelete() {
        String username = "it_user_" + IdUtils.nextEntityId();
        Account account = buildTestAccount(username);

        assertTrue(accountService.save(account));
        assertTrue(accountService.removeById(account.getId()));

        LambdaQueryWrapper<Account> wrapper = Wrappers.<Account>lambdaQuery()
                .eq(Account::getUsername, username);
        Account foundAfterDelete = accountService.getOne(wrapper);

        assertNull(foundAfterDelete);
    }

    /**
     * 验证 SecureId AOP 切面生效：返回值中的内部 ID 应被隐藏并生成 externalId。
     */
    @Test
    void shouldHideInternalIdWithSecureIdAspect() {
        Account account = new Account();
        account.setId(IdUtils.nextEntityId());
        account.setUsername("flyingTest");
        account.setPassword("12345689");
        account.setRole("user");
        account.setEmail("flyingTest@163.com");

        AccountVO accountVO = new AccountVO();
        BeanUtils.copyProperties(account, accountVO);
        Result<AccountVO> result = Result.success(accountVO);

        TestSecureIdService testService = applicationContext.getBean(TestSecureIdService.class);
        Result<AccountVO> processedResult = testService.processWithSecureId(result);

        assertNotNull(processedResult);
        assertNotNull(processedResult.getData());

        AccountVO resultVO = processedResult.getData();
        assertNull(resultVO.getId(), "ID应该被隐藏");
        assertNotNull(resultVO.getExternalId(), "外部ID应该被设置");
    }
}
