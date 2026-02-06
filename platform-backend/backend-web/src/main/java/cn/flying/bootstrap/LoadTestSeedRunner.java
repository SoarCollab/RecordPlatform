package cn.flying.bootstrap;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Local profile only: seed a tenant + account for repeatable k6 load tests.
 * <p>
 * This avoids the email verification flow for registration.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "loadtest.seed", name = "enabled", havingValue = "true")
public class LoadTestSeedRunner implements ApplicationRunner {

    private final TenantMapper tenantMapper;
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${loadtest.seed.tenant-id:1}")
    private long tenantId;

    @Value("${loadtest.seed.tenant-code:loadtest}")
    private String tenantCode;

    @Value("${loadtest.seed.tenant-name:LoadTest}")
    private String tenantName;

    @Value("${loadtest.seed.username:loadtest}")
    private String username;

    @Value("${loadtest.seed.password:loadtest123}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        ensureTenant();
        ensureAccount();
    }

    private void ensureTenant() {
        Tenant existing = tenantMapper.selectById(tenantId);
        if (existing != null) {
            return;
        }

        Tenant tenant = new Tenant()
                .setId(tenantId)
                .setCode(tenantCode)
                .setName(tenantName)
                .setStatus(1)
                .setCreateTime(new Date());

        tenantMapper.insert(tenant);
        log.info("Load test seed: created tenant id={}, code={}", tenantId, tenantCode);
    }

    private void ensureAccount() {
        TenantContext.runWithTenant(tenantId, () -> {
            Account existing = accountMapper.selectOne(new LambdaQueryWrapper<Account>()
                    .eq(Account::getUsername, username)
                    .eq(Account::getTenantId, tenantId));
            if (existing != null) {
                return;
            }

            Account account = new Account();
            account.setId(nextUserId());
            account.setUsername(username);
            account.setEmail(username + "@local.test");
            account.setPassword(passwordEncoder.encode(password));
            account.setRole("user");
            account.setAvatar(null);
            account.setNickname("Load Test");
            account.setTenantId(tenantId);
            account.setRegisterTime(new Date());
            account.setUpdateTime(new Date());
            account.setDeleted(0);

            accountMapper.insert(account);
            log.info("Load test seed: created account username={}, tenantId={}", username, tenantId);
        });
    }

    private Long nextUserId() {
        try {
            return IdUtils.nextUserId();
        } catch (RuntimeException ex) {
            // Local seed should not fail hard if IdUtils is not initialized (e.g. unit tests)
            return System.currentTimeMillis();
        }
    }
}
