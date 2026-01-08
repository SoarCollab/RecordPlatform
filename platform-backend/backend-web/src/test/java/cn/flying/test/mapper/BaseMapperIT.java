package cn.flying.test.mapper;

import cn.flying.common.tenant.TenantContext;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public abstract class BaseMapperIT extends BaseIntegrationTest {

    protected static final Long TEST_TENANT_ID = 1L;
    protected static final Long TEST_USER_ID = 100L;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId(TEST_TENANT_ID);
    }

    @AfterEach
    void tearDownTenant() {
        TenantContext.clear();
    }

    protected <T> T withTenant(Long tenantId, java.util.function.Supplier<T> action) {
        return TenantContext.callWithTenant(tenantId, action::get);
    }

    protected void runWithTenant(Long tenantId, Runnable action) {
        TenantContext.runWithTenant(tenantId, action);
    }
}
