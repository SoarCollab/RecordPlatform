package cn.flying.bootstrap;

import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("LoadTestSeedRunner Tests")
class LoadTestSeedRunnerTest {

    @Test
    @DisplayName("should insert tenant and account when missing")
    void shouldInsertTenantAndAccountWhenMissing() {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

        when(tenantMapper.selectById(anyLong())).thenReturn(null);
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        LoadTestSeedRunner runner = new LoadTestSeedRunner(tenantMapper, accountMapper, passwordEncoder);
        ReflectionTestUtils.setField(runner, "tenantId", 1L);
        ReflectionTestUtils.setField(runner, "tenantCode", "loadtest");
        ReflectionTestUtils.setField(runner, "tenantName", "LoadTest");
        ReflectionTestUtils.setField(runner, "username", "loadtest");
        ReflectionTestUtils.setField(runner, "password", "loadtest123");

        runner.run(null);

        verify(tenantMapper).insert(any(Tenant.class));
        verify(accountMapper).insert(any(Account.class));
    }
}
