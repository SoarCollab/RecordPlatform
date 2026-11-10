package cn.flying.dao.config;

import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * Tenant interceptor that honours {@code @InterceptorIgnore(tenantLine)} on auto-count statements.
 * <p>
 * MyBatis-Plus rewrites a paged query into {@code <id>_mpCount} before executing the count SQL and
 * looks the ignore strategy up by that rewritten id, which never matches the mapper method. Paged
 * statements that deliberately read global rows (for example {@code tenant_id = 0} permission
 * definitions) therefore lost those rows in {@code page.getTotal()}. This subclass resolves the
 * strategy from the originating statement id instead.
 */
public class CountAwareTenantLineInnerInterceptor extends TenantLineInnerInterceptor {

    /** Suffix MyBatis-Plus appends when it derives the auto-count statement. */
    private static final String AUTO_COUNT_SUFFIX = "_mpCount";

    /**
     * Creates the interceptor with the shared tenant handler.
     *
     * @param tenantLineHandler tenant column and ignore-table policy
     */
    public CountAwareTenantLineInnerInterceptor(TenantLineHandler tenantLineHandler) {
        super(tenantLineHandler);
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,
                            ResultHandler resultHandler, BoundSql boundSql) {
        if (inheritsTenantLineIgnore(ms.getId())) {
            return;
        }
        super.beforeQuery(executor, ms, parameter, rowBounds, resultHandler, boundSql);
    }

    /**
     * Reports whether the statement (or the paged statement it was derived from) opts out of tenant filtering.
     *
     * @param statementId MyBatis statement id, possibly with the auto-count suffix
     * @return true when the tenant condition must not be appended
     */
    private boolean inheritsTenantLineIgnore(String statementId) {
        if (statementId == null) {
            return false;
        }
        if (isTenantLineIgnored(statementId)) {
            return true;
        }
        if (statementId.endsWith(AUTO_COUNT_SUFFIX)) {
            return isTenantLineIgnored(statementId.substring(0, statementId.length() - AUTO_COUNT_SUFFIX.length()));
        }
        return false;
    }

    /** Reads the cached ignore strategy for one statement id. */
    private boolean isTenantLineIgnored(String statementId) {
        IgnoreStrategy strategy = InterceptorIgnoreHelper.getIgnoreStrategy(statementId);
        return strategy != null && Boolean.TRUE.equals(strategy.getTenantLine());
    }
}
