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
            "sys_audit_config",
            "sys_config",
            "processed_message",
            "proof_signing_key"
            // 注意：sys_operation_log 已移除，审计日志保持租户隔离
            // 超级管理员可通过 TenantContext.setIgnoreIsolation(true) 临时绕过
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
        // 静态忽略：某些系统表始终不做租户过滤。
        // 生成 SQL 的表名已统一加反引号（db-config.table-format），比较前需先归一化，
        // 否则 `tenant` 与 tenant 不相等，系统表会被错误地追加 tenant_id 条件。
        return IGNORE_TABLES.contains(normalizeTableName(tableName));
    }

    /**
     * 去掉表名两侧的引号（反引号、双引号、方括号），便于与忽略清单比较。
     *
     * @param tableName jsqlparser 解析出的表名
     * @return 归一化后的表名
     */
    private static String normalizeTableName(String tableName) {
        if (tableName == null) {
            return "";
        }
        String normalized = tableName.trim();
        while (!normalized.isEmpty()) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            boolean quoted = (first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']');
            if (!quoted || normalized.length() < 2) {
                break;
            }
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }
}
