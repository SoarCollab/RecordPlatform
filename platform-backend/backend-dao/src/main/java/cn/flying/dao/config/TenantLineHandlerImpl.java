package cn.flying.dao.config;

import cn.flying.common.tenant.TenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Set;

/**
 * Tenant line handler for shared-schema isolation.
 * <p>
 * Automatically adds tenant_id condition to SQL queries.
 * Supports dynamic bypass via TenantContext.isIgnoreIsolation().
 */
public class TenantLineHandlerImpl implements TenantLineHandler {

    private static final Set<String> IGNORE_TABLES = Set.of(
            "tenant",
            "sys_config",
            "processed_message",
            "sys_operation_log"  // 审计日志不做租户隔离（超级管理员功能）
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
        // 动态忽略：当 TenantContext 设置了忽略标记时，跳过所有表的租户过滤
        if (TenantContext.isIgnoreIsolation()) {
            return true;
        }
        // 静态忽略：某些系统表始终不做租户过滤
        return IGNORE_TABLES.contains(tableName);
    }
}
