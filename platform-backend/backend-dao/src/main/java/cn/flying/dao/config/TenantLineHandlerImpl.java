package cn.flying.dao.config;

import cn.flying.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * Tenant line handler for shared-schema isolation.
 */
public class TenantLineHandlerImpl implements TenantLineHandler {

    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenant",
            "sys_config",
            "processed_message"
    );

    @Override
    public Expression getTenantId() {
        return new LongValue(TenantContext.getTenantIdOrDefault());
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return IGNORE_TABLES.contains(tableName);
    }
}
